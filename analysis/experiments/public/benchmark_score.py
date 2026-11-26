"""Scores a vkr-taint report on OWASP BenchmarkJava against its expected results.

A test case is flagged in a category when a chain of the report ends in that category's CWE and names
the test case: the sink site (BenchmarkTest01234#doPost:57, or an inner class BenchmarkTest01234$Test),
else the entry. Per category: TP/FP/FN/TN, true positive rate (recall), false positive rate,
precision, and the Benchmark score TPR - FPR (the OWASP Benchmark scorecard metric). The other
categories of the Benchmark (crypto, hash, weakrand, securecookie, trustbound, ldapi, xpathi) are
not taint problems the catalogues cover and are not scored.

usage: benchmark_score.py <report.json> <expectedresults-1.2.csv> [--flagged out.csv] [--json out.json]
"""
import argparse
import csv
import json
import re
import sys

CATEGORIES = {'sqli': '89', 'xss': '79', 'cmdi': '78', 'pathtraver': '22'}
LOCAL = re.compile(r'CWE-(\d+) @ (\S+)$')
TEST = re.compile(r'BenchmarkTest\d{5}')


def sink_sites(report):
    """{(test name, cwe): sink sites} of the report's chains."""
    out = {}
    for chain in report['chains']:
        text = chain if isinstance(chain, str) else json.dumps(chain)
        m = LOCAL.search(text.rstrip('"]} '))
        if not m:
            continue
        cwe, site = m.groups()
        test = TEST.search(site) or TEST.search(text)
        if test:
            out.setdefault((test.group(0), cwe), set()).add(site)
    return out


def flagged(report):
    """{(test name, cwe)} of the report's chains."""
    return set(sink_sites(report))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('report')
    ap.add_argument('expected')
    ap.add_argument('--flagged', help='write the flagged test cases with their expected label')
    ap.add_argument('--json', help='write the counts per category (input of precision_gate.py)')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    hits = flagged(json.load(open(a.report, encoding='utf-8')))
    rows = []
    with open(a.expected, encoding='utf-8') as f:
        for r in csv.reader(f):
            if r and not r[0].startswith('#'):
                rows.append((r[0].strip(), r[1].strip(), r[2].strip() == 'true'))
    print(f'{"category":<11} {"CWE":>4} {"TP":>4} {"FP":>4} {"FN":>4} {"TN":>4} {"TPR":>7} {"FPR":>7} '
          f'{"prec.":>7} {"score":>7}')
    total = [0, 0, 0, 0]
    out = []
    counts = {}
    for cat, cwe in CATEGORIES.items():
        tp = fp = fn = tn = 0
        for name, c, real in rows:
            if c != cat:
                continue
            hit = (name, cwe) in hits
            tp += hit and real
            fp += hit and not real
            fn += real and not hit
            tn += not real and not hit
            if hit:
                out.append((name, cat, real))
        counts[cat] = {'TP': tp, 'FP': fp, 'FN': fn, 'TN': tn}
        for i, v in enumerate((tp, fp, fn, tn)):
            total[i] += v
        tpr = tp / (tp + fn) if tp + fn else 0
        fpr = fp / (fp + tn) if fp + tn else 0
        prec = tp / (tp + fp) if tp + fp else 0
        print(f'{cat:<11} {cwe:>4} {tp:>4} {fp:>4} {fn:>4} {tn:>4} {tpr:>7.1%} {fpr:>7.1%} {prec:>7.1%} '
              f'{tpr - fpr:>+7.1%}')
    tp, fp, fn, tn = total
    tpr, fpr = tp / (tp + fn), fp / (fp + tn)
    print(f'{"all four":<11} {"":>4} {tp:>4} {fp:>4} {fn:>4} {tn:>4} {tpr:>7.1%} {fpr:>7.1%} '
          f'{tp / (tp + fp) if tp + fp else 0:>7.1%} {tpr - fpr:>+7.1%}')
    names = {n for n, _, _ in rows}
    stray = sorted({(n, c) for n, c in hits if n not in names or
                    not any(n == r[0] and CATEGORIES.get(r[1]) == c for r in rows)})
    print(f'flags in a test case of another category (not scored): {len(stray)}')
    if a.json:
        counts['all'] = dict(zip(('TP', 'FP', 'FN', 'TN'), total))
        with open(a.json, 'w', encoding='utf-8') as f:
            json.dump(counts, f, indent=1, sort_keys=True)
    if a.flagged:
        with open(a.flagged, 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['test', 'category', 'real'])
            w.writerows(sorted(out))


if __name__ == '__main__':
    main()
