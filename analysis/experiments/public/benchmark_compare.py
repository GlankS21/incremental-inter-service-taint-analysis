"""Per test case of OWASP BenchmarkJava (sqli, xss, cmdi, pathtraver): expected label, DeltaTaint flag and
sink lines, CodeQL flag (standard taint queries) and rules, CodeQL flag with every rule of the workflow.

usage: benchmark_compare.py <vkr-taint-report.json> <codeql java.sarif> <expectedresults-1.2.csv> <out.csv> <blob-url>
"""
import collections
import csv
import json
import re
import sys

CWE_OF = {'sqli': '89', 'xss': '79', 'cmdi': '78', 'pathtraver': '22'}
CQL_CWES = {'sqli': {89, 564}, 'xss': {79}, 'cmdi': {78, 88}, 'pathtraver': {22, 23, 36, 73}}
CQL_TAINT = {'sqli': {'java/sql-injection'}, 'xss': {'java/xss'},
             'cmdi': {'java/command-line-injection', 'java/exec-tainted-environment'},
             'pathtraver': {'java/path-injection'}}
LOCAL = re.compile(r'CWE-(\d+) @ (\S+)$')
TEST = re.compile(r'BenchmarkTest\d{5}')
CWE = re.compile(r'external/cwe/cwe-0*(\d+)')

report, sarif, expected, out, blob = sys.argv[1:6]

dt = collections.defaultdict(set)            # (test, cwe) -> sink sites
for chain in json.load(open(report, encoding='utf-8'))['chains']:
    text = chain if isinstance(chain, str) else json.dumps(chain)
    m = LOCAL.search(text.rstrip('"]} '))
    if not m:
        continue
    cwe, site = m.groups()
    t = TEST.search(site) or TEST.search(text)
    if t:
        dt[(t.group(0), cwe)].add(site)

cq_taint = collections.defaultdict(set)      # (test, cat) -> rule:line
cq_all = collections.defaultdict(set)
s = json.load(open(sarif, encoding='utf-8'))
for run in s['runs']:
    rules = {}
    for comp in [run['tool']['driver']] + run['tool'].get('extensions', []):
        for r in comp.get('rules', []):
            tags = r.get('properties', {}).get('tags', [])
            rules[r['id']] = {int(m.group(1)) for x in tags for m in [CWE.search(x)] if m}
    for res in run.get('results', []):
        rid = res.get('ruleId')
        for loc in res.get('locations', []):
            pl = loc['physicalLocation']
            t = TEST.search(pl['artifactLocation'].get('uri', ''))
            if not t:
                continue
            line = pl.get('region', {}).get('startLine', '')
            for cat in CWE_OF:
                if rules.get(rid, set()) & CQL_CWES[cat]:
                    cq_all[(t.group(0), cat)].add(f'{rid}:{line}')
                if rid in CQL_TAINT[cat]:
                    cq_taint[(t.group(0), cat)].add(f'{rid}:{line}')


def outcome(flag, real):
    return ('TP' if real else 'FP') if flag else ('FN' if real else 'TN')


with open(expected, encoding='utf-8') as f, open(out, 'w', newline='', encoding='utf-8-sig') as o:
    w = csv.writer(o)
    w.writerow(['test', 'category', 'cwe', 'real_vulnerability', 'deltataint', 'deltataint_result',
                'deltataint_sinks', 'codeql', 'codeql_result', 'codeql_rules', 'codeql_all_rules',
                'codeql_all_result', 'agree', 'source'])
    for r in csv.reader(f):
        if not r or r[0].startswith('#') or r[1].strip() not in CWE_OF:
            continue
        name, cat, real = r[0].strip(), r[1].strip(), r[2].strip() == 'true'
        sinks = dt.get((name, CWE_OF[cat]), set())
        cq = cq_taint.get((name, cat), set())
        ca = cq_all.get((name, cat), set())
        d, c = outcome(bool(sinks), real), outcome(bool(cq), real)
        w.writerow([name, cat, CWE_OF[cat], 'yes' if real else 'no', 'yes' if sinks else 'no', d,
                    ' '.join(sorted(sinks)), 'yes' if cq else 'no', c, ' '.join(sorted(cq)),
                    ' '.join(sorted(ca)), outcome(bool(ca), real),
                    'yes' if bool(sinks) == bool(cq) else 'no',
                    f'{blob}/src/main/java/org/owasp/benchmark/testcode/{name}.java'])
