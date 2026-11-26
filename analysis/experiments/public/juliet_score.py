"""Scores vkr-taint reports on the servlet test cases of Juliet Java 1.3 (built by build_juliet.py).

Ground truth comes from Juliet's naming: a test case (the files of one name, a/b/c... and the _81
base/bad/good classes) has one bad flow, in methods named bad*, and its good flows in methods named
goodG2B*/goodB2G* (good source to bad sink, bad source to fixed sink); flow variant 81 puts each flow
in a class of its own (_81_bad, _81_goodG2B, _81_goodB2G) whose sink method is action(). A sink site
of a report (Class#method:line) belongs to the flow its method, else its class, names.

A test case is found (TP) when a site of its bad flow is reported, else it is an FN; every good flow
with a reported site is an FP.

usage: juliet_score.py <report.json>... --src <build-juliet dir> [--list out.csv] [--json out.json]
"""
import argparse
import collections
import csv
import json
import os
import re
import sys

SITE = re.compile(r'CWE-(\d+) @ ([\w$]+)#([\w$<>]+):(\d+)')
CASE = re.compile(r'^(CWE\d+_\w+?__\w+?_\d+)(?:[a-z]|_bad|_base|_goodG2B|_goodB2G)?$')
METHOD_FLOW = re.compile(r'^(bad|goodG2B\d*|goodB2G\d*)')
CLASS_FLOW = re.compile(r'_81_(bad|goodG2B|goodB2G)$')
DECL = re.compile(r'\bvoid\s+(goodG2B\d*|goodB2G\d*)(?:Sink)?\s*\(')


def case_of(cls):
    m = CASE.match(cls.split('$')[0])
    return m.group(1) if m else None


def ground_truth(src):
    """{case: (CWE, {good flow names})} from the sources of every juliet-* module."""
    truth = {}
    for mod in sorted(os.listdir(src)):
        base = os.path.join(src, mod, 'src', 'main', 'java', 'testcases')
        if not mod.startswith('juliet-') or not os.path.isdir(base):
            continue
        for root, _, files in os.walk(base):
            for f in files:
                case = case_of(f[:-5]) if f.endswith('.java') else None
                if not case:
                    continue
                goods = truth.setdefault(case, (case.split('__')[0], set()))[1]
                goods.update(DECL.findall(open(os.path.join(root, f), encoding='latin-1').read()))
                m = CLASS_FLOW.search(f[:-5])
                if m and m.group(1) != 'bad':
                    goods.add(m.group(1))
    return truth


def flow_of(cls, method):
    m = METHOD_FLOW.match(method)
    if m:
        return m.group(1)
    m = CLASS_FLOW.search(cls.split('$')[0])
    return m.group(1) if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('reports', nargs='+')
    ap.add_argument('--src', required=True)
    ap.add_argument('--list')
    ap.add_argument('--json')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    truth = ground_truth(a.src)
    flagged = collections.defaultdict(set)   # case -> flows with a reported site
    rows, other = [], 0
    for path in a.reports:
        for chain in json.load(open(path, encoding='utf-8'))['chains']:
            m = SITE.search(chain if isinstance(chain, str) else json.dumps(chain))
            if not m:
                continue
            cwe, cls, method, line = m.groups()
            case, flow = case_of(cls), flow_of(cls, method)
            if case not in truth or flow is None:
                other += 1
                rows.append(('?', cls, method, line, cwe, 'outside a flow'))
                continue
            flagged[case].add(flow)
            rows.append((truth[case][0], cls, method, line, cwe, 'TP' if flow == 'bad' else 'FP'))
    per = collections.defaultdict(collections.Counter)
    for case, (cwe, goods) in truth.items():
        c = per[cwe]
        c['cases'] += 1
        c['TP' if 'bad' in flagged[case] else 'FN'] += 1
        c['goods'] += len(goods)
        c['FP'] += len(flagged[case] & goods)
        if 'bad' not in flagged[case]:
            rows.append((cwe, case, 'bad', '', '', 'FN'))
    print(f'{"cwe":<32} {"cases":>6} {"TP":>5} {"FN":>5} {"recall":>7} {"good":>6} {"FP":>5} {"FPR":>6}')
    total = collections.Counter()
    for cwe in sorted(per):
        c = per[cwe]
        total.update(c)
        print(f'{cwe:<32} {c["cases"]:>6} {c["TP"]:>5} {c["FN"]:>5} {c["TP"] / c["cases"]:>7.1%} '
              f'{c["goods"]:>6} {c["FP"]:>5} {c["FP"] / max(c["goods"], 1):>6.1%}')
    print(f'{"all":<32} {total["cases"]:>6} {total["TP"]:>5} {total["FN"]:>5} {total["TP"] / total["cases"]:>7.1%} '
          f'{total["goods"]:>6} {total["FP"]:>5} {total["FP"] / max(total["goods"], 1):>6.1%}')
    if other:
        print(f'{other} reported sites are in no flow of a test case (testcasesupport, ...)')
    if a.list:
        with open(a.list, 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['cwe', 'class', 'method', 'line', 'sink_cwe', 'result'])
            w.writerows(sorted(rows))
    if a.json:
        with open(a.json, 'w', encoding='utf-8') as f:
            json.dump({k: dict(v) for k, v in [*per.items(), ('all', total)]}, f, indent=1, sort_keys=True)


if __name__ == '__main__':
    main()
