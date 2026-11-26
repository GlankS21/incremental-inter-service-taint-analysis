"""Regression gate of a change to the analysis: compares the scores of the public suites with a frozen
baseline and fails when any group (category, directory, CWE) loses a true positive or gains a false
positive. A recall gain bought with new false positives does not pass.

Inputs are the --json outputs of benchmark_score.py, securibench_score.py and juliet_score.py; the
baseline is results/gate-baseline.json: {suite: {group: {TP, FP, ...}}}.

usage: precision_gate.py --baseline results/gate-baseline.json [--benchmark b.json] [--securibench s.json]
       [--juliet j.json] [--freeze]   (--freeze writes the given scores as the new baseline)
"""
import argparse
import json
import sys

SUITES = ('benchmark', 'securibench', 'juliet')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--baseline', required=True)
    for s in SUITES:
        ap.add_argument(f'--{s}')
    ap.add_argument('--freeze', action='store_true')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    current = {s: json.load(open(getattr(a, s), encoding='utf-8')) for s in SUITES if getattr(a, s)}
    if a.freeze:
        try:
            base = json.load(open(a.baseline, encoding='utf-8'))
        except FileNotFoundError:
            base = {}
        base.update(current)
        with open(a.baseline, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(base, f, indent=1, sort_keys=True)
            f.write('\n')
        print(f'baseline {a.baseline}: {", ".join(sorted(current))} frozen')
        return 0
    base = json.load(open(a.baseline, encoding='utf-8'))
    failed = []
    print(f'{"suite":<12} {"group":<32} {"TP":>11} {"FP":>11}')
    for suite, groups in current.items():
        for group in sorted(set(groups) | set(base.get(suite, {}))):
            b, c = base.get(suite, {}).get(group, {}), groups.get(group, {})
            tp0, tp1, fp0, fp1 = b.get('TP', 0), c.get('TP', 0), b.get('FP', 0), c.get('FP', 0)
            mark = ''
            if tp1 < tp0 or fp1 > fp0:
                mark = '  REGRESSION'
                failed.append(f'{suite}/{group}')
            if tp1 != tp0 or fp1 != fp0 or group == 'all':
                print(f'{suite:<12} {group:<32} {tp0:>4} -> {tp1:<4} {fp0:>4} -> {fp1:<4}{mark}')
    missing = [s for s in base if s not in current]
    if missing:
        print(f'not checked (no scores given): {", ".join(missing)}')
    print('gate: ' + ('FAILED (' + ', '.join(failed) + ')' if failed else 'passed'))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
