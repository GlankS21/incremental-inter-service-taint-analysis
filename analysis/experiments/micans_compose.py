"""Composition of per-service runs on a Micans benchmark system, compared with the whole-system
Micans run and with the flows the Micans authors verified.

Each service was analysed alone by micans_benchmark.py --per-service-method. Its run log holds
  TaintFlow{<source point> -> <method>[i@Ln] invokestatic taint.SourceSink.sink(..)/0}   local finding
  ChannelFlow{<source point> -> <caller>[i@Ln] <call>/<arg>/<field path>}              outgoing channel end
A source point is <handler>/<param> (a String parameter, vkr.plugin.StringParamSources) or
<handler>/<param>/<field> (a String field of a DTO parameter: Micans' own web-endpoint sources and,
with vkr.listenerTaint, listener payloads).

Chains start only at Micans' own sources (DTO fields of web endpoints) and follow the channels of
the channel-extractor model: a Feign call into the endpoint it reaches, a send into the listeners
its routing key reaches. The taint enters the receiving handler at the same parameter index for
HTTP and at the payload for AMQP, at the same field when the field path names a String field of
the argument, otherwise at any input of that parameter (coarse). A flow is identified like
run.py of the Micans artifact compares them: source point and sink method, without line numbers.

usage: micans_compose.py <model.json> <per-service runs dir> <whole run.log> <verified-flow dir>
"""
import glob
import json
import os
import re
import sys

FLOW = re.compile(r'^(TaintFlow|ChannelFlow)\{(.*?) -> (<.*)\}$')
SOURCE = re.compile(r'^(<[^>]*>)/(\d+)(?:/(\w+))?$')
METHOD = re.compile(r'^<([\w.$]+): \S+ ([\w$<>]+)\(')
CALL_SITE = re.compile(r'^(<[^>]*>)\[\d+@L-?\d+\] (.*)/(\d+)/(\S*)$')
INVOKED = re.compile(r'<([\w.$]+): \S+ (\w+)\([^)]*\)>')


def sink_key(sink):
    """<method> of a sink point, without the statement index and line"""
    return re.sub(r'\[\d+@L-?\d+\].*$', '', sink).strip()


def cls_method(sig):
    m = METHOD.match(sig)
    return (m.group(1), m.group(2)) if m else None


def verified(directory):
    keys = set()
    for f in glob.glob(os.path.join(directory, '*.dot')):
        if 'taint-flow-graph' in f:
            continue
        text = open(f, encoding='utf-8').read()
        src = re.search(r'\\n([^"\n]*)" \[fillcolor=gold', text).group(1)
        snk = re.search(r'\\n([^"\n]*)" \[fillcolor=deepskyblue', text).group(1)
        keys.add((src.strip(), sink_key(snk)))
    return keys


def whole_flows(log):
    keys = set()
    for line in open(log, encoding='utf-8', errors='replace'):
        m = FLOW.match(line.strip())
        if m and m.group(1) == 'TaintFlow':
            keys.add((m.group(2).strip(), sink_key(m.group(3))))
    return keys


def load_runs(runs_dir):
    """(handler class, method) -> list of (param, field or None, exit); exit is ('LOCAL', sink method)
    or ('CALL', caller class, caller method, invoked class, invoked method, arg, field path)"""
    entries = {}
    for log in glob.glob(os.path.join(runs_dir, '*', 'run.log')):
        for line in open(log, encoding='utf-8', errors='replace'):
            m = FLOW.match(line.strip())
            if not m:
                continue
            kind, source, target = m.groups()
            s = SOURCE.match(source.strip())
            if not s:
                continue
            handler, param, field = s.groups()
            key = cls_method(handler)
            if kind == 'TaintFlow':
                x = ('LOCAL', sink_key(target))
            else:
                c = CALL_SITE.match(target)
                if not c:
                    continue
                caller, call, arg, path = c.groups()
                # Tai-e prints an invoke without the declaring class of the callee ("$r1.m($r2)"); a
                # signature is present only in logs of a newer ChannelArgSink ("... -> <C: T m(..)>")
                invoked = INVOKED.findall(call.split('] ', 1)[-1])
                name = invoked[-1][1] if invoked else (re.findall(r'\.([\w$]+)\(', call) or [None])[-1]
                if not name:
                    continue
                x = ('CALL', *cls_method(caller), invoked[-1][0] if invoked else None, name, int(arg), path)
            entries.setdefault(key, []).append((int(param), field, x, handler))
    return entries


def main(model_path, runs_dir, whole_log, verified_dir):
    model = json.load(open(model_path, encoding='utf-8'))
    endpoints = {e['id']: e for e in model['endpoints']}
    listeners = {l['id']: l for l in model['listeners']}
    clients = {c['id']: c for c in model['httpClients']}
    publishers = {p['id']: p for p in model['publishers']}
    out = {}
    for ch in model['channels']:
        out.setdefault(ch['from'], []).append(ch)
    listener_methods = {(l['handler']['className'], l['handler']['method']) for l in listeners.values()}
    entries = load_runs(runs_dir)

    def channels_of(x):
        """the channels an outgoing call is the sending end of: (kind, receiving handler)"""
        _, caller_cls, caller_m, inv_cls, inv_m, arg, path = x
        result = []
        for c in clients.values():
            if c['declaration']['method'] == inv_m and inv_cls in (None, c['declaration']['className']) and any(
                    (s['className'], s['method']) == (caller_cls, caller_m) for s in c['callSites']):
                for ch in out.get(c['id'], []):
                    h = endpoints[ch['to']]['handler']
                    result.append(('HTTP', (h['className'], h['method'])))
        if inv_m in ('convertAndSend', 'send', 'convertSendAndReceive', 'convertSendAndReceiveAsType'):
            for p in publishers.values():
                if (p['sendSite']['className'], p['sendSite']['method']) == (caller_cls, caller_m):
                    for ch in out.get(p['id'], []):
                        h = listeners[ch['to']]['handler']
                        result.append(('AMQP', (h['className'], h['method'])))
        return result

    def inputs(kind, handler, arg, path):
        """inputs of the receiving handler the taint arrives at"""
        cands = entries.get(handler, [])
        field = path if path and re.fullmatch(r'\w+', path) else None
        if kind == 'HTTP':
            cands = [e for e in cands if e[0] == arg]
        if field:
            exact = [e for e in cands if e[1] == field]
            return exact
        if not path:  # the argument itself: a String parameter
            return [e for e in cands if e[1] is None]
        return cands  # nested field path: coarse

    composed, hops = set(), {}
    for handler, items in entries.items():
        if handler in listener_methods:
            continue
        for param, field, x, source_sig in items:
            if field is None:
                continue  # chains start at Micans' own sources only
            source = f'{source_sig}/{param}/{field}'
            stack = [((param, field, x, source_sig), 0, frozenset())]
            while stack:
                (p, f, ex, sig), depth, seen = stack.pop()
                if ex[0] == 'LOCAL':
                    composed.add((source, ex[1]))
                    hops[(source, ex[1])] = min(hops.get((source, ex[1]), 99), depth)
                    continue
                for kind, target in channels_of(ex):
                    for nxt in inputs(kind, target, ex[5], ex[6]):
                        key = (target, nxt[0], nxt[1], nxt[2])
                        if key not in seen and depth < 6:
                            stack.append((nxt, depth + 1, seen | {key}))

    ver = verified(verified_dir)
    whole = whole_flows(whole_log)
    intra = {k for k, d in hops.items() if d == 0}
    print(f'composed flows: {len(composed)} (within one service {len(intra)}, across services {len(composed - intra)})')
    print(f'whole-system Micans flows: {len(whole)}; common with composed: {len(composed & whole)}')
    print(f'verified flows: {len(ver)}; found by composition: {len(ver & composed)}; '
          f'by whole-system Micans: {len(ver & whole)}; by per-service runs alone: {len(ver & intra)}')
    for v in sorted(ver - composed):
        print('  missed:', v[0].split(': ')[1][:90], '=>', v[1].split(': ')[1][:70])
    json.dump({'composed': sorted(composed), 'hops': {f'{a} => {b}': d for (a, b), d in sorted(hops.items())}},
              open(os.path.join(runs_dir, 'composed.json'), 'w', encoding='utf-8'), indent=1)


if __name__ == '__main__':
    main(*sys.argv[1:5])
