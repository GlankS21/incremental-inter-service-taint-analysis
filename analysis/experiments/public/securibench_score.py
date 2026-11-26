"""Scores a vkr-taint report on Securibench Micro (too4words/securibench-micro) against the
/* BAD */ markers of its sources.

Each /* BAD */ comment marks the last line of a vulnerable statement; the statement runs from the line
after the previous statement end (a line ending in ';', '{' or '}') to the marked line. A sink site of
the report (Class#method:line, inner classes Class$1) is a TP when its line falls in a BAD statement of
that class's file and an FP otherwise; a BAD statement no sink site falls in is an FN. Results are
given per directory (basic, collections, ...) and in total, with the BAD statements whose sink is in
no catalogue of the tool (response.sendRedirect: open redirect) counted apart.

usage: securibench_score.py <report.json> <securibench-micro/src> [--exclude Basic40,...] [--list out.csv]
--exclude: classes left out of the build (Basic40 needs com.oreilly cos.jar); their BAD statements are
not scored
"""
import argparse
import collections
import csv
import json
import os
import re
import sys

SITE = re.compile(r'CWE-(\d+) @ ([\w$]+)#([\w$<>]+):(\d+)')
OUT_OF_SCOPE = re.compile(r'sendRedirect')


def bad_statements(src):
    """{class simple name: (directory, [(first line, last line, text)])} from the BAD markers."""
    out = {}
    for root, _, files in os.walk(src):
        for f in files:
            if not f.endswith('.java'):
                continue
            lines = open(os.path.join(root, f), encoding='latin-1').read().split('\n')
            stmts = []
            for i, line in enumerate(lines, 1):
                if '/* BAD */' not in line:
                    continue
                first = i
                while first > 1:
                    prev = re.sub(r'//.*|/\*.*?\*/', '', lines[first - 2]).strip()
                    if prev.endswith((';', '{', '}')) or not prev and first < i:
                        break
                    first -= 1
                # "an error should be flagged either on this or the previous line" (Basic22): the
                # statement before counts as well
                if 'previous line' in ' '.join(lines[max(0, first - 4):i]):
                    while first > 1 and not re.sub(r'//.*|/\*.*?\*/', '', lines[first - 2]).strip():
                        first -= 1
                    first -= 1
                text =' '.join(re.sub(r'/\*.*?\*/', '', l).strip() for l in lines[first - 1:i])
                stmts.append((first, i, text))
            out[f[:-5]] = (os.path.basename(root), stmts)
    return out


def sink_sites(report):
    """{(class, line, cwe)} of the report's chains; an inner class counts as its outer class."""
    sites = set()
    for chain in report['chains']:
        m = SITE.search(chain if isinstance(chain, str) else json.dumps(chain))
        if m:
            cwe, cls, _, line = m.groups()
            sites.add((cls.split('$')[0], int(line), cwe))
    return sites


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('report')
    ap.add_argument('src')
    ap.add_argument('--exclude', default='', help='comma-separated classes left out of the build')
    ap.add_argument('--list', help='write every BAD statement and flagged site with its result')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    bad = bad_statements(a.src)
    for cls in filter(None, a.exclude.split(',')):
        bad.pop(cls, None)
    sites = sink_sites(json.load(open(a.report, encoding='utf-8')))
    per = collections.defaultdict(lambda: collections.Counter())
    rows = []
    hit = set()
    for cls, line, cwe in sorted(sites):
        directory, stmts = bad.get(cls, ('?', []))
        match = next(((f, l) for f, l, _ in stmts if f <= line <= l), None)
        if match:
            hit.add((cls, match))
            rows.append((directory, cls, line, cwe, 'TP', ''))
        else:
            per[directory]['FP'] += 1
            rows.append((directory, cls, line, cwe, 'FP', ''))
    for cls, (directory, stmts) in bad.items():
        for f, l, text in stmts:
            if (cls, (f, l)) in hit:
                per[directory]['TP'] += 1
            elif OUT_OF_SCOPE.search(text):
                per[directory]['FN out of scope'] += 1
                rows.append((directory, cls, l, '', 'FN (no catalogue)', text))
            else:
                per[directory]['FN'] += 1
                rows.append((directory, cls, l, '', 'FN', text))
    print(f'{"directory":<15} {"TP":>4} {"FP":>4} {"FN":>4} {"FN*":>4} {"recall":>7} {"prec.":>7}')
    total = collections.Counter()
    for directory in sorted(per):
        c = per[directory]
        total.update(c)
        rec = c['TP'] / (c['TP'] + c['FN']) if c['TP'] + c['FN'] else float('nan')
        prec = c['TP'] / (c['TP'] + c['FP']) if c['TP'] + c['FP'] else float('nan')
        print(f'{directory:<15} {c["TP"]:>4} {c["FP"]:>4} {c["FN"]:>4} {c["FN out of scope"]:>4} '
              f'{rec:>7.1%} {prec:>7.1%}')
    rec = total['TP'] / (total['TP'] + total['FN'])
    prec = total['TP'] / (total['TP'] + total['FP']) if total['TP'] + total['FP'] else float('nan')
    print(f'{"all":<15} {total["TP"]:>4} {total["FP"]:>4} {total["FN"]:>4} {total["FN out of scope"]:>4} '
          f'{rec:>7.1%} {prec:>7.1%}')
    print('FN*: BAD statements whose sink is in no catalogue (open redirect), not in recall')
    if a.list:
        with open(a.list, 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['directory', 'class', 'line', 'cwe', 'result', 'statement'])
            w.writerows(sorted(rows))


if __name__ == '__main__':
    main()
