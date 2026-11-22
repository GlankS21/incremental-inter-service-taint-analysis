"""Writes the chains of an incremental.py report as SARIF 2.1.0 (GitHub code scanning and other viewers).

One result per chain. The primary location is the sink (Class#method:line of the chain's last
service); the code flow runs through the handler of every endpoint or listener the chain passes,
whose file and line come from the channel model. Chains are fingerprinted without line numbers
(incremental.chain_key), so a finding moved by an unrelated edit keeps its identity; with a
baseline report, results carry baselineState new or unchanged.

usage: sarif.py <report.json> <channels.json> <project root> <out.sarif>
"""
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from incremental import chain_key  # noqa: E402

RULES = {
    'CWE-89': ('SqlInjection', 'SQL injection',
               'A request parameter reaches an SQL query (JPA, JdbcTemplate or a MyBatis ${} mapper), '
               'possibly through other services.'),
    'CWE-117': ('LogInjection', 'Log injection',
                'A request parameter reaches a log statement unneutralized, possibly through other services.'),
}
LEVEL = {'CWE-89': 'error', 'CWE-117': 'warning'}
SEVERITY = {'CWE-89': '8.8', 'CWE-117': '5.3'}  # GitHub orders code scanning alerts by security-severity
SINK = re.compile(r'\s+(CWE-\d+) @ ([\w$.]+)#([\w$<>]+):(\d+)$')
EDGE = re.compile(r'\s+--(\w+) (.*?)-->\s+')
NODE = re.compile(r'^(\w+):([^:]+):(.*?)(?: <([^>]*)>)?$')
SKIP_DIRS = {'.git', 'target', 'build', 'node_modules', '.idea', '.gradle', '.mvn'}


def java_files(root):
    """Simple class name -> repo-relative paths of its source files (main sources first)."""
    index = {}
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for f in files:
            if f.endswith('.java'):
                rel = os.path.relpath(os.path.join(d, f), root).replace(os.sep, '/')
                index.setdefault(f[:-5], []).append(rel)
    for paths in index.values():
        paths.sort(key=lambda p: ('/src/main/' not in p, p))
    return index


def parse(chain):
    """chain -> (nodes [(kind, service, name, param)], edges [(kind, label)], cwe, sink class, method, line)."""
    m = SINK.search(chain)
    if not m:
        raise ValueError(f'not a chain to a sink: {chain}')
    parts = EDGE.split(chain[:m.start()])
    nodes = []
    for text in parts[0::3]:
        n = NODE.match(text.strip())
        nodes.append(n.groups() if n else ('?', '?', text.strip(), None))
    edges = list(zip(parts[1::3], parts[2::3]))
    return nodes, edges, m.group(1), m.group(2), m.group(3), int(m.group(4))


def location(uri, line, text):
    loc = {'physicalLocation': {'artifactLocation': {'uri': uri, 'uriBaseId': '%SRCROOT%'}}}
    if line:
        loc['physicalLocation']['region'] = {'startLine': line}
    loc['message'] = {'text': text}
    return loc


def sink_file(index, cls, service, handler_file):
    paths = index.get(cls.split('.')[-1].split('$')[0], [])
    module = handler_file.split('/src/')[0] + '/' if handler_file and '/src/' in handler_file else None
    for prefer in (module, f'{service}/'):
        hit = [p for p in paths if prefer and p.startswith(prefer)]
        if hit:
            return hit[0]
    return paths[0] if paths else None


def to_sarif(report, model, root, baseline=None):
    handlers = {e['id']: e.get('handler') or {} for key in ('endpoints', 'listeners') for e in model.get(key, [])}
    index = java_files(root)
    old = {chain_key(c) for c in baseline['chains']} if baseline else None
    results = []
    for chain in report['chains']:
        nodes, edges, cwe, cls, method, line = parse(chain)
        flow, last_file = [], None
        for i, (kind, service, name, param) in enumerate(nodes):
            h = handlers.get(f'{kind}:{service}:{name}', {})
            last_file = h.get('file') or last_file
            what = f'{service}: {name}' + (f' (parameter {param})' if param else '')
            if i < len(edges):
                what += f', then {edges[i][0]} {edges[i][1]}'
            if h.get('file'):
                flow.append(location(h['file'], h.get('line'), what))
        service = nodes[-1][1]
        sink_uri = sink_file(index, cls, service, last_file)
        sink_text = f'{cwe} sink in {cls}#{method}'
        sink = location(sink_uri, line, sink_text) if sink_uri else {'message': {'text': sink_text}}
        if sink_uri:
            flow.append(sink)
        services = [n[1] for n in nodes]
        entry = nodes[0]
        result = {
            'ruleId': cwe,
            'level': LEVEL.get(cwe, 'warning'),
            'message': {'text': f'{RULES.get(cwe, (cwe, cwe))[1]}: {entry[2]} ({entry[1]}) reaches '
                                f'{cls}#{method}' + (f' across {len(services) - 1} service call(s): '
                                                     f'{" -> ".join(services)}' if len(services) > 1 else '')},
            'locations': [sink],
            'partialFingerprints': {'vkrChain/v1': hashlib.sha256(chain_key(chain).encode()).hexdigest()},
            'properties': {'chain': chain, 'crossService': len(services) > 1},
        }
        if flow:
            result['codeFlows'] = [{'threadFlows': [{'locations': [{'location': l} for l in flow]}]}]
        if old is not None:
            result['baselineState'] = 'unchanged' if chain_key(chain) in old else 'new'
        results.append(result)
    used = sorted({r['ruleId'] for r in results} | set(RULES))
    rules = [{'id': r, 'name': RULES.get(r, (r,))[0],
              'shortDescription': {'text': RULES.get(r, (r, r))[1]},
              'fullDescription': {'text': RULES.get(r, (r, r, r))[2]},
              'helpUri': f'https://cwe.mitre.org/data/definitions/{r.split("-")[1]}.html',
              'properties': {'tags': ['security', 'taint', r.lower()],
                             'security-severity': SEVERITY.get(r, '5.0')}} for r in used]
    run = {'tool': {'driver': {'name': 'vkr-taint', 'rules': rules}},
           'results': results}
    if report.get('failed'):
        run['invocations'] = [{'executionSuccessful': False, 'toolExecutionNotifications': [
            {'level': 'error', 'message': {'text': f'{s}: analysis failed, its chains are missing'}}
            for s in report['failed']]}]
    else:
        run['invocations'] = [{'executionSuccessful': True}]
    return {'$schema': 'https://json.schemastore.org/sarif-2.1.0.json', 'version': '2.1.0', 'runs': [run]}


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    report, model, root, out = sys.argv[1:]
    sarif = to_sarif(json.load(open(report, encoding='utf-8')), json.load(open(model, encoding='utf-8')), root)
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(sarif, f, indent=1)
    print(f'{len(sarif["runs"][0]["results"])} results -> {out}')


if __name__ == '__main__':
    main()
