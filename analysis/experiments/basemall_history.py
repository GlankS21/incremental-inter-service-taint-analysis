"""Replay of a benchmark system's commit history as CI runs, without rebuilding: which services each
commit makes the incremental method reanalyse, and what that costs with the per-service times measured
on the benchmark version.

Two impact rules are compared.
  module: a service is reanalysed when a commit changes Java code or a pom.xml of a module on its
          application classpath (app-info.yml of the Micans artifact lists them: the service module,
          the API modules it calls and the common modules).
  class:  a modified or deleted Java file counts only for the services whose last analysis reached its
          class (reachable-classes.txt of vkr.plugin.ReachableClasses); an added Java file and a pom.xml
          change still count for every service using the module, since a new class can be a new
          component and a build change can change any library.
A change to the root pom or an aggregator pom reanalyses every service. A commit that changes only
other files (configuration, SQL, docs) reanalyses nothing; when it touches configuration the channel
model is extracted and the summaries composed again, which takes seconds.

The reachable classes come from one analysis of the benchmark version, not from the analysis before
each commit as in CI; classes the history adds or renames are covered by the added-file rule.

usage: basemall_history.py <repo> <app-info.yml> <system> <per-service runs dir> <whole seconds>
                           <first commit> [--last ref] [--csv out.csv]
"""
import argparse
import collections
import csv
import os
import re
import subprocess
import sys

import yaml


def git(repo, *args):
    return subprocess.run(['git', '-C', repo, *args], capture_output=True, text=True,
                          encoding='utf-8', check=True).stdout


def java_class(path):
    m = re.search(r'/src/main/java/(.+)\.java$', path)
    return m.group(1).replace('/', '.') if m else None


def main():
    ap = argparse.ArgumentParser()
    for a in ('repo', 'app_info', 'system', 'runs_dir', 'whole_seconds', 'first'):
        ap.add_argument(a)
    ap.add_argument('--last', default='HEAD')
    ap.add_argument('--csv')
    a = ap.parse_args()

    info = yaml.safe_load(open(a.app_info, encoding='utf-8'))[a.system]
    users = collections.defaultdict(set)  # module -> services whose application classpath holds it
    for svc, cp in info.items():
        for jar in cp['apps']:
            users[re.sub(r'-\d+(\.\d+)*$', '', jar.split('/')[-1][:-4])].add(svc)
    seconds = {}
    for row in csv.DictReader(open(os.path.join(a.runs_dir, 'runs.csv'), encoding='utf-8')):
        seconds[row['run']] = int(row['seconds'])  # the last run of a service wins
    reach = {}
    for svc in info:
        f = os.path.join(a.runs_dir, svc, 'reachable-classes.txt')
        reach[svc] = set(open(f, encoding='utf-8').read().split()) if os.path.exists(f) else None
    whole = float(a.whole_seconds)

    commits = git(a.repo, 'log', '--first-parent', '--reverse', '--format=%h %ad', '--date=short',
                  f'{a.first}^..{a.last}').split('\n')
    rows = []
    for h, date in (c.split() for c in commits if c):
        changes = []
        for line in git(a.repo, 'diff-tree', '--no-commit-id', '--name-status', '-r', '-m', '--first-parent',
                        h).split('\n'):
            if not line:
                continue
            status, *paths = line.split('\t')
            # a rename is a deletion of the old file and an addition of the new one
            if status.startswith('R'):
                changes += [('D', paths[0]), ('A', paths[1])]
            else:
                changes.append((status[0], paths[0]))
        changes = [(s, p) for s, p in changes if p.startswith('gruul/')]
        code = [(s, p) for s, p in changes if p.endswith('.java') or p.endswith('pom.xml')]
        config = [p for _, p in changes if re.search(r'\.(ya?ml|properties)$', p)]
        by_module, by_class = set(), set()
        for status, path in code:
            parts = path.split('/')
            mod = next((p for p in reversed(parts[:-1]) if p in users), None)
            module_users = users[mod] if mod else set(info)
            by_module |= module_users
            cls = java_class(path)
            if status == 'A' or cls is None:
                by_class |= module_users
            else:
                by_class |= {s for s in module_users if reach[s] is None or cls in reach[s]}
        kind = 'code' if code else ('config' if config else ('other' if changes else 'outside'))
        rows.append({'commit': h, 'date': date, 'kind': kind, 'files': len(changes),
                     'module_services': len(by_module), 'module_s': sum(seconds[s] for s in by_module),
                     'class_services': len(by_class), 'class_s': sum(seconds[s] for s in by_class),
                     'class_wall_s': max((seconds[s] for s in by_class), default=0),
                     'names': ' '.join(sorted(s.replace('gruul-', '') for s in by_class))})

    if a.csv:
        with open(a.csv, 'w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
    code = [r for r in rows if r['kind'] == 'code']
    print(f'{len(rows)} commits: {dict(collections.Counter(r["kind"] for r in rows))}')
    print(f'whole-system Micans on each code commit: {whole * len(code) / 3600:.2f} h')
    for rule in ('module', 'class'):
        dist = sorted(collections.Counter(r[f'{rule}_services'] for r in code).items())
        total = sum(r[f'{rule}_s'] for r in code)
        print(f'{rule} rule: services per code commit {dist}; sequential {total / 3600:.2f} h '
              f'(x{whole * len(code) / total:.2f} against whole-system)')
    wall = sum(r['class_wall_s'] for r in code)
    print(f'class rule, affected services in parallel: {wall / 3600:.2f} h wall '
          f'(x{whole * len(code) / wall:.2f})')
    within = sum(1 for r in code if r['class_s'] <= whole)
    print(f'code commits where the class rule costs no more than whole-system: {within}/{len(code)}')


if __name__ == '__main__':
    main()
