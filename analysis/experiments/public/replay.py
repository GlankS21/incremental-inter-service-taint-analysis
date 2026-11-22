"""Replays a public repo's commit history as CI runs of the incremental tool.

For each first-parent commit from <first> to <last>: check it out in a replay copy of the clone,
build it in a container (build_public.sh) when it changes build input (a pom.xml or anything under a
src/ directory), and run incremental.py with the cache kept across commits and the previous report
as baseline. The first commit is a cold run. With --cold-check the last commit is also analysed from
an empty cache, and its chains are compared with those of the incremental run (equivalence).

A failed build is recorded and the commit is not analysed; the next commit is built again.
Reruns resume: a commit whose report exists is not built or analysed again.

usage: replay.py <clone> <first> <last> --config <vkr-taint.yml> --image <maven image>
                 [--mvn-arg ARG ...] [--cold-check]
       the config's project.root must be ${VKR_PUBLIC_BUILD}/<clone dir name>
output: $VKR_EXP/replay/<name>/ (VKR_EXP: default $LOCALAPPDATA/vkr-exp, else ~/.cache/vkr-exp)
         (NN-<commit>.json reports, replay.csv, *.log)
env:   JAVA_HOME, VKR_MICANS_JAR as for incremental.py
"""
import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, os.pardir, os.pardir, 'taint', 'scripts')
# experiment data stays outside the repo (and outside synced folders such as OneDrive)
L = os.environ.get('VKR_EXP') or (os.path.join(os.environ['LOCALAPPDATA'], 'vkr-exp') if os.environ.get('LOCALAPPDATA')
                                  else os.path.join(os.path.expanduser('~'), '.cache', 'vkr-exp'))
FIELDS = ['n', 'commit', 'date', 'subject', 'build_input_files', 'build', 'build_s', 'run_s', 'services',
          'analysed', 'failed', 'chains', 'new', 'removed', 'unchecked']


def git(repo, *args):
    # longpaths: train-ticket's 2019 tree has paths past Windows' 260-character limit
    return subprocess.run(['git', '-C', repo, '-c', 'core.longpaths=true', *args], capture_output=True, text=True, encoding='utf-8',
                          check=True).stdout


def build_input(repo, prev, h):
    if prev is None:
        return ['(first commit)']
    paths = git(repo, 'diff', '--name-only', prev, h).split('\n')
    return [p for p in paths if p and (p.endswith('pom.xml') or '/src/' in f'/{p}')]


def run_tool(cfg, work, build_root, baseline, out, log):
    if not os.path.isdir(os.path.join(work, 'jre-home')):
        os.makedirs(work, exist_ok=True)
        shutil.copytree(os.path.join(L, 'work', 'jre-home'), os.path.join(work, 'jre-home'))
    env = dict(os.environ, VKR_WORK=work, VKR_PUBLIC_BUILD=build_root, PYTHONIOENCODING='utf-8')
    cmd = [sys.executable, '-I', 'incremental.py', '--config', cfg, '--out', out, '--no-gate']
    if baseline:
        cmd += ['--baseline', baseline]
    start = time.time()
    with open(log, 'w', encoding='utf-8') as f:
        code = subprocess.run(cmd, cwd=SCRIPTS, env=env, stdout=f, stderr=subprocess.STDOUT).returncode
    return code, round(time.time() - start)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('clone')
    ap.add_argument('first')
    ap.add_argument('last')
    ap.add_argument('--config', required=True)
    ap.add_argument('--image', required=True)
    ap.add_argument('--mvn-arg', action='append', default=[])
    ap.add_argument('--cold-check', action='store_true')
    a = ap.parse_args()
    cfg = os.path.abspath(a.config)
    name = os.path.basename(os.path.normpath(a.clone))
    repo = os.path.join(L, 'public', 'replay', name)        # checked out commit by commit
    build_root = os.path.join(L, 'build-replay')            # project.root = build_root/name
    work = os.path.join(L, 'public-work', f'replay-{name}')  # cache kept across commits
    out_dir = os.path.join(L, 'replay', name)
    os.makedirs(out_dir, exist_ok=True)
    if not os.path.isdir(repo):
        shutil.copytree(a.clone, repo, symlinks=True)

    commits = git(repo, 'log', '--first-parent', '--reverse', '--format=%h\t%ad\t%s', '--date=short',
                  f'{a.first}^..{a.last}').strip().split('\n')
    csv_path = os.path.join(out_dir, 'replay.csv')
    done = {r['commit']: r for r in csv.DictReader(open(csv_path, encoding='utf-8'))} \
        if os.path.exists(csv_path) else {}
    rows, baseline, built, resumed = [], None, None, False  # built: the commit whose build is in build_root
    for n, line in enumerate(commits):
        h, date, subject = line.split('\t', 2)
        report = os.path.join(out_dir, f'{n:02d}-{h}.json')
        if h in done and os.path.exists(report):
            rows.append(done[h])
            baseline, built, resumed = report, h, True
            continue
        if resumed:  # the build dir may hold a build interrupted after the last report
            built, resumed = None, False
        git(repo, 'checkout', '-q', '-f', h)
        git(repo, 'clean', '-fdxq')
        changed = build_input(repo, built, h)
        row = {'n': n, 'commit': h, 'date': date, 'subject': subject[:60], 'build_input_files': len(changed),
               'build': 'reused', 'build_s': 0}
        if changed:
            start = time.time()
            with open(os.path.join(out_dir, f'{n:02d}-{h}-build.log'), 'w', encoding='utf-8') as f:
                # sh, not bash: on Windows "bash" may resolve to WSL's System32\bash.exe
                code = subprocess.run(['sh', os.path.join(HERE, 'build_public.sh'), repo,
                                       os.path.join(build_root, name), a.image, *a.mvn_arg],
                                      stdout=f, stderr=subprocess.STDOUT).returncode
            row.update(build='ok' if code == 0 else f'failed ({code})', build_s=round(time.time() - start))
            built = h if code == 0 else None
            if code:
                rows.append(row)
                print(f'{n:02d} {h} build failed, see {f.name}', flush=True)
                write(csv_path, rows)
                continue
        code, secs = run_tool(cfg, work, build_root, baseline, report,
                              os.path.join(out_dir, f'{n:02d}-{h}-run.log'))
        row['run_s'] = secs
        if code or not os.path.exists(report):
            row['failed'] = f'run exit {code}'
        else:
            r = json.load(open(report, encoding='utf-8'))
            svcs = r['services']
            row.update(services=len(svcs),
                       analysed=' '.join(s for s, v in svcs.items() if not v.get('runCached') and not v.get('failed')),
                       failed=' '.join(r.get('failed', [])), chains=len(r['chains']),
                       new=len(r.get('new', [])), removed=len(r.get('removed', [])),  # none without a baseline
                       unchecked=len(r.get('unchecked', [])))
            for s in r.get('failed', []):  # the work dir's run.log is overwritten by the next commit
                log = os.path.join(work, s, 'run.log')
                if os.path.exists(log):
                    shutil.copyfile(log, os.path.join(out_dir, f'{n:02d}-{h}-{s}.log'))
            baseline = report
        rows.append(row)
        write(csv_path, rows)
        print(f"{n:02d} {h} build={row['build']} {row['build_s']}s run={secs}s "
              f"analysed=[{row.get('analysed', '')}] chains={row.get('chains')} new={row.get('new')} "
              f"removed={row.get('removed')} failed=[{row.get('failed', '')}]", flush=True)

    if a.cold_check and baseline:
        cold = os.path.join(out_dir, 'cold-last.json')
        if not os.path.exists(cold):
            cold_work = os.path.join(L, 'public-work', f'replay-{name}-cold')
            shutil.rmtree(cold_work, ignore_errors=True)
            code, secs = run_tool(cfg, cold_work, build_root, None, cold, os.path.join(out_dir, 'cold-last-run.log'))
            print(f'cold run on the last commit: exit {code}, {secs}s', flush=True)
        inc = set(json.load(open(baseline, encoding='utf-8'))['chains'])
        full = set(json.load(open(cold, encoding='utf-8'))['chains'])
        print(f'equivalence: incremental {len(inc)} chains, cold {len(full)}, '
              f'only incremental {len(inc - full)}, only cold {len(full - inc)}')
        for c in sorted(inc - full):
            print('  only incremental:', c)
        for c in sorted(full - inc):
            print('  only cold:', c)


def write(path, rows):
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction='ignore')
        w.writeheader()
        w.writerows(rows)


if __name__ == '__main__':
    main()
