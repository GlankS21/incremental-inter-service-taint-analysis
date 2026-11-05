"""Scaling check on a system of the Micans benchmark: one Micans run of the whole system against one
run per service, with Micans as published (its own CLI, its taint config, no vkr plugins).

The question is whether reanalysing one service is cheaper than reanalysing the whole system when
the system is larger than the testbed. Every run is logged with -Xlog:gc; the largest heap after a
collection is the live set, the largest heap before a collection the peak occupancy.

The benchmark jars come from the Micans artifact (benchmark-jars.tar.xz, extracted so that
<bench>/benchmarks/app-info.yml and <bench>/benchmarks/<system>-taint/ exist). Library exclusions
are the ones run.py of the artifact applies to work around Soot frontend failures.

usage: micans_benchmark.py <bench dir> <system> <jre-home> [--xmx 24g] [--only svc1,svc2] [--whole-only]
output: <bench dir>/runs/<system>/<run>/{args.txt,run.log,gc.log}, a table on stdout and in runs.csv
"""
import argparse
import csv
import os
import re
import subprocess
import time

import yaml

JAVA_HOME = os.environ.get('JAVA_HOME', 'C:/Program Files/Java/jdk-24')
TAINT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'taint')
MICANS_JAR = os.environ.get('MICANS_JAR', 'C:/Users/asgat/OneDrive/Máy tính/WorkSpace/ITMO - Thsi/'
                            'vkr-artifacts/miscans/src-extract/micans-source/build/micans.jar')
# run.py of the Micans artifact drops these libraries for these systems
EXCLUDED_LIBS = {'basemall': ('/bcprov-jdk', '/okhttp'), 'mall4cloud': ('/seata-all',)}


def classpaths(bench, system):
    info = yaml.safe_load(open(os.path.join(bench, 'benchmarks', 'app-info.yml'), encoding='utf-8'))[system]
    root = os.path.join(bench, 'benchmarks', f'{system}-taint')
    path = lambda p: os.path.normpath(os.path.join(root, p))
    out = {}
    for svc, cp in info.items():
        libs = [l for l in cp['libs'] if not any(x in l for x in EXCLUDED_LIBS.get(system, ()))]
        out[svc] = ([path(a) for a in cp['apps']], [path(l) for l in libs])
    return out


def micans_cmd(out, services, xmx, gc):
    """Micans as published, through its CLI"""
    # the classpaths are far too long for the Windows command line: picocli reads them from an @file
    with open(os.path.join(out, 'args.txt'), 'w', encoding='utf-8') as f:
        for opt, flag in (('apps', '--app'), ('libs', '--lib')):
            for svc, (apps, libs) in services.items():
                paths = apps if opt == 'apps' else libs
                f.write(f'{flag}\n"{svc}={",".join(paths)}"\n'.replace('\\', '/'))
        f.write(f'--taint\n--output-dir\n"{os.path.join(out, "tai-e")}"\n'.replace('\\', '/'))
    return [os.path.join(JAVA_HOME, 'bin', 'java'), f'-Xmx{xmx}', '-Xss512m', f'-Xlog:gc:file={gc}',
            '-jar', MICANS_JAR, '@' + os.path.join(out, 'args.txt')]


def method_cmd(out, services, xmx, gc):
    """one service through the method's launcher: Micans' modelling plus the channel-end plugins only
    (no vkr precision plugins, so the flows are comparable with the whole-system Micans run)"""
    (svc, (apps, libs)), = services.items()
    libfile = os.path.join(out, 'libjars.txt')
    open(libfile, 'w', encoding='utf-8').write('\n'.join(libs) + '\n')
    plugins = 'vkr.plugin.ListenerEntryPoints,vkr.plugin.StringParamSources,vkr.plugin.ChannelArgSink'
    return [os.path.join(JAVA_HOME, 'bin', 'java'), f'-Xmx{xmx}', '-Xss512m', f'-Xlog:gc:file={gc}',
            f'-Dvkr.plugins={plugins}', '-Dvkr.channelSinks=true', '-Dvkr.listenerTaint=true',
            '-cp', f'{os.path.join(TAINT, "launcher", "classes")};{MICANS_JAR}', 'vkr.MicansTaintMain',
            svc, ','.join(apps), '@' + libfile, os.path.join(TAINT, 'config', 'micans-benchmark.yml'),
            os.path.join(out, 'tai-e'), 'ci']


def run(bench, system, jre_home, name, services, xmx, method=False):
    out = os.path.join(bench, 'runs' + ('-method' if method else ''), system, name)
    os.makedirs(out, exist_ok=True)
    gc = os.path.join(out, 'gc.log')
    cmd = (method_cmd if method else micans_cmd)(out, services, xmx, gc)
    t0 = time.time()
    with open(os.path.join(out, 'run.log'), 'w', encoding='utf-8') as log:
        # Tai-e resolves the JRE model relative to the working directory
        code = subprocess.run(cmd, cwd=jre_home, stdout=log, stderr=subprocess.STDOUT).returncode
    seconds = time.time() - t0
    pairs = [(int(a), int(b)) for a, b in re.findall(r'(\d+)M->(\d+)M', open(gc, encoding='utf-8').read())]
    text = open(os.path.join(out, 'run.log'), encoding='utf-8', errors='replace').read()
    grab = lambda rx: (re.search(rx, text) or [None, ''])[1].replace(',', '')
    return {'run': name, 'services': len(services), 'exit': code, 'seconds': round(seconds),
            # the run exits with 1 after the taint report: Tai-e's Profiler plugin fails on a static field
            # without a JField; the reported flows are complete by then
            'pta_seconds': grab(r'(?:\[Pointer analysis\]|pta finishes,) elapsed time: ([\d.]+)s'),
            'reachable': grab(r'#reachable methods:\s+([\d,]+)'),
            'live_peak_mb': max((b for _, b in pairs), default=0),
            'peak_mb': max((a for a, _ in pairs), default=0),
            'flows': grab(r'Detected (\d+) taint flow'),
            'channel_flows': grab(r'ChannelArgSink: (\d+) channel flow'),
            'oom': 'OutOfMemoryError' in text}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('bench')
    ap.add_argument('system')
    ap.add_argument('jre_home')
    ap.add_argument('--xmx', default='24g')
    ap.add_argument('--only')
    ap.add_argument('--whole-only', action='store_true')
    ap.add_argument('--per-service-method', action='store_true',
                    help='analyse each service alone with the channel-end plugins of the method')
    a = ap.parse_args()
    cps = classpaths(a.bench, a.system)
    method = a.per_service_method
    rows = [run(a.bench, a.system, a.jre_home, 'whole', cps, a.xmx)] if not (a.only or method) else []
    if not a.whole_only:
        for svc in (a.only.split(',') if a.only else cps):
            rows.append(run(a.bench, a.system, a.jre_home, svc, {svc: cps[svc]}, a.xmx, method))
    path = os.path.join(a.bench, 'runs' + ('-method' if method else ''), a.system, 'runs.csv')
    new = not os.path.exists(path)
    with open(path, 'a', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        if new:
            w.writeheader()
        w.writerows(rows)
    for r in rows:
        print(r)


if __name__ == '__main__':
    main()
