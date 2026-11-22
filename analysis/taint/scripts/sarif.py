"""Writes the chains of an incremental.py report as SARIF 2.1.0 (GitHub code scanning and other viewers).

One result per finding (incremental.finding_key: entry point and sink method), so a flow printed
once per source label and once per channel path is one alert. The primary location is the sink
(Class#method:line of the finding's shortest chain); each chain is a code flow through the handler
of every endpoint or listener it passes, whose file and line come from the channel model. The
fingerprint is the finding key, which has no line numbers, so a finding moved by an unrelated edit
keeps its identity; with a baseline report, results carry baselineState new or unchanged.

usage: sarif.py <report.json> <channels.json> <project root> <out.sarif>
"""
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from incremental import chain_key, finding_key, group_findings  # noqa: E402

RULES = {
    'CWE-89': ('SqlInjection', 'SQL injection',
               'A request parameter reaches an SQL query (JPA, JdbcTemplate or a MyBatis ${} mapper), '
               'possibly through other services.'),
    'CWE-117': ('LogInjection', 'Log injection',
                'A request parameter reaches a log statement unneutralized, possibly through other services.'),
    'CWE-79': ('CrossSiteScripting', 'Cross-site scripting',
               'A request parameter, or a value stored from one, is returned as the response body without '
               'HTML encoding, possibly through other services.'),
    'CWE-918': ('ServerSideRequestForgery', 'Server-side request forgery',
                'A request parameter decides the URL the service opens, possibly through other services.'),
    'CWE-1336': ('TemplateInjection', 'Template injection',
                 'A request parameter is compiled as a template (FreeMarker, Thymeleaf, Velocity), possibly '
                 'through other services.'),
}
LEVEL = {'CWE-89': 'error', 'CWE-117': 'warning', 'CWE-79': 'error', 'CWE-918': 'error', 'CWE-1336': 'error'}
# GitHub orders code scanning alerts by security-severity
SEVERITY = {'CWE-89': '8.8', 'CWE-117': '5.3', 'CWE-79': '6.1', 'CWE-918': '8.6', 'CWE-1336': '9.8'}
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


def git_root(path):
    """The enclosing git work tree, whose root code scanning resolves SARIF uris against; else path."""
    d = os.path.abspath(path)
    while True:
        if os.path.exists(os.path.join(d, '.git')):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.path.abspath(path)
        d = parent


def location(uri, line, text, prefix=''):
    loc = {'physicalLocation': {'artifactLocation': {'uri': prefix + uri, 'uriBaseId': '%SRCROOT%'}}}
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


def representative(chains):
    """The chain shown for a finding: the fewest hops, then the most specific source label."""
    return min(chains, key=lambda c: (c.count('-->'), -len(parse(c)[0][0][3] or ''), c))


def source_labels(chains):
    """The distinct source labels of the entry point, in order of appearance."""
    labels = []
    for c in chains:
        param = parse(c)[0][0][3]
        if param and param not in labels:
            labels.append(param)
    return labels


def to_sarif(report, model, root, baseline=None, uri_base=None):
    """uri_base: directory the uris are relative to (default: the git root above root)."""
    prefix = os.path.relpath(os.path.abspath(root), uri_base or git_root(root)).replace(os.sep, '/')
    prefix = '' if prefix == '.' else prefix + '/'
    handlers = {e['id']: e.get('handler') or {} for key in ('endpoints', 'listeners') for e in model.get(key, [])}
    index = java_files(root)
    old = {finding_key(c) for c in baseline['chains']} if baseline else None

    def code_flow(chain):
        nodes, edges, cwe, cls, method, line = parse(chain)
        flow, last_file = [], None
        for i, (kind, service, name, param) in enumerate(nodes):
            h = handlers.get(f'{kind}:{service}:{name}', {})
            last_file = h.get('file') or last_file
            what = f'{service}: {name}' + (f' (parameter {param})' if param else '')
            if i < len(edges):
                what += f', then {edges[i][0]} {edges[i][1]}'
            if h.get('file'):
                flow.append(location(h['file'], h.get('line'), what, prefix))
        sink_uri = sink_file(index, cls, nodes[-1][1], last_file)
        sink_text = f'{cwe} sink in {cls}#{method}'
        sink = location(sink_uri, line, sink_text, prefix) if sink_uri else {'message': {'text': sink_text}}
        if sink_uri:
            flow.append(sink)
        return sink, flow

    results = []
    for finding in report.get('findings') or group_findings(report['chains']):
        chains = finding['chains']
        main = representative(chains)
        nodes, edges, cwe, cls, method, line = parse(main)
        sink, _ = code_flow(main)
        flows = [f for f in (code_flow(c)[1] for c in [main] + [c for c in chains if c != main]) if f]
        services = [n[1] for n in nodes]
        entry = nodes[0]
        sources = source_labels(chains)
        result = {
            'ruleId': cwe,
            'level': LEVEL.get(cwe, 'warning'),
            'message': {'text': f'{RULES.get(cwe, (cwe, cwe))[1]}: {entry[2]} ({entry[1]}) reaches '
                                f'{cls}#{method}' + (f' across {len(services) - 1} service call(s): '
                                                     f'{" -> ".join(services)}' if len(services) > 1 else '')
                                + (f'; source {", ".join(sources)}' if sources else '')
                                + (f' ({len(chains)} chains)' if len(chains) > 1 else '')},
            'locations': [sink],
            'partialFingerprints': {'vkrFinding/v1': hashlib.sha256(finding['key'].encode()).hexdigest()},
            'properties': {'finding': finding['key'], 'chains': chains,
                           'crossService': any('-->' in c for c in chains)},
        }
        if flows:
            result['codeFlows'] = [{'threadFlows': [{'locations': [{'location': l} for l in f]}]} for f in flows]
        if old is not None:
            result['baselineState'] = 'unchanged' if finding['key'] in old else 'new'
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
