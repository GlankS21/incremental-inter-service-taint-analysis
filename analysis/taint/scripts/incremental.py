"""Incremental inter-service taint analysis (ADR-001, option B).

Each service is re-analysed only when something its Micans run reads has changed. Two cache levels:
  run key     = hash(service classes, shared-module classes, library jars, base config (generated
                from the channel model and the bytecode, or hand-written), discovery, overrides,
                config generators, launcher, Micans jar, JDK, context sensitivity) -> taint/store flows
  summary key = hash(run key, channel model, summarize.py)                   -> summary.json
Composition is cheap and always recomputed from the summaries. Its chains to a sink are grouped
into findings (finding_key: entry point and sink method, so the source labels and channel paths of
one flow are one finding) and compared with a baseline report; new findings fail the run with
--gate (exit 1).
A service whose analysis fails does not stop the run: the report lists it under "failed", baseline
chains through it go to "unchecked" (not "removed"), and with --gate the run exits 2.

Everything project-specific comes from vkr-taint.yml (vkr_config.py): services, shared modules,
toolchain (JDK, Micans jar), context sensitivity, heap, world cache, gate. The project must be built
(target/classes and the Spring Boot jar of each service); the tool does not build.

usage: incremental.py [--config vkr-taint.yml] [--services a,b,c] [--model channels.json]
                      [--baseline report.json] [--out report.json] [--gate | --no-gate]
       --config defaults to $VKR_CONFIG, else vkr-taint.yml in the current directory or a parent;
       --model defaults to a fresh channel-extractor run on project.root ($VKR_CHANNEL_EXTRACTOR or
       analysis/channel-extractor/target/channel-extractor-0.1.0-SNAPSHOT.jar)
env:   VKR_WORK (default work/), plus the variables vkr-taint.yml references
"""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vkr_config  # noqa: E402
import vkr_prepare  # noqa: E402

T = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.environ.get('VKR_WORK', os.path.join(T, 'work'))
CACHE = os.path.join(WORK, 'cache')
EXTRACTOR_JAR = os.environ.get('VKR_CHANNEL_EXTRACTOR') or os.path.join(
    T, '..', 'channel-extractor', 'target', 'channel-extractor-0.1.0-SNAPSHOT.jar')
CONFIG_SCRIPTS = ['prepare.sh', 'prep_micans.py', 'pack_jre.py', 'mk_dto_fields.py', 'mk_event_fields.py',
                  'mk_field_config.py', 'mk_entity_fields.py', 'mk_db_config.py', 'run_taint.sh',
                  'gen_taint_config.py', 'vkr_prepare.py', 'vkr_config.py', 'vkr_yaml.py']
CFG = None  # the loaded vkr-taint.yml (main)
FLOW_PREFIXES = ('TaintFlow{', 'StoreFlow{', 'ResponseFlow{')


class Hasher:
    """sha256 over named parts, so that moving bytes between parts changes the key."""

    def __init__(self):
        self.h = hashlib.sha256()

    def part(self, name, data):
        self.h.update(f'{name}\0{len(data)}\0'.encode())
        self.h.update(data)

    def file(self, name, path):
        with open(path, 'rb') as f:
            self.part(name, f.read())

    def tree(self, name, root, suffix=''):
        for dirpath, _, files in sorted(os.walk(root)):
            for fn in sorted(files):
                if fn.endswith(suffix):
                    p = os.path.join(dirpath, fn)
                    self.file(f'{name}/{os.path.relpath(p, root)}', p)

    def hexdigest(self):
        return self.h.hexdigest()


def jar_classes(h, name, data):
    """A rebuilt jar differs in its entry timestamps only: hash the class entries' names and CRCs."""
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for e in sorted(z.infolist(), key=lambda e: e.filename):
            if e.filename.endswith('.class'):
                h.part(f'{name}!{e.filename}', e.CRC.to_bytes(4, 'big'))


def is_shared_jar(name):
    return any(name == f'{m["artifact"]}.jar' or name.startswith(f'{m["artifact"]}-') for m in CFG['shared'])


def run_key(svc, base, discovery):
    m = vkr_prepare.module(CFG, svc)
    h = Hasher()
    h.tree('app', m['classes'], '.class')
    with zipfile.ZipFile(m['jar']) as boot:
        for e in sorted(boot.infolist(), key=lambda e: e.filename):
            if not (e.filename.startswith(vkr_config.LIB_PREFIXES) and e.filename.endswith('.jar')):
                continue
            if is_shared_jar(os.path.basename(e.filename)):
                jar_classes(h, 'common', boot.read(e))  # built with the services: content only
            else:
                h.part(e.filename, e.CRC.to_bytes(4, 'big'))  # released artifact: the jar itself
    # the base config is generated from the channel model: its text, not the model, is the input
    h.file('config', base)
    h.file('discovery', discovery)
    if CFG['taint']['overrides']:
        h.file('overrides', CFG['taint']['overrides'])
    for s in CONFIG_SCRIPTS:
        h.file(s, os.path.join(T, 'scripts', s))
    h.tree('launcher', os.path.join(T, 'launcher', 'src'), '.java')
    h.tree('jdk-stubs', os.path.join(T, 'jdk-stubs'), '.java')
    h.tree('servlet-stubs', os.path.join(T, 'servlet-stubs'), '.java')
    # entries, not bytes: a Micans jar rebuilt from the same source and patches (the Docker image after
    # an Actions cache eviction) differs in its zip timestamps only
    with zipfile.ZipFile(CFG['toolchain']['micans_jar']) as z:
        for e in sorted(z.infolist(), key=lambda e: e.filename):
            if not e.is_dir():
                h.part(f'micans!{e.filename}', e.CRC.to_bytes(4, 'big'))
    h.file('jdk', os.path.join(CFG['toolchain']['java_home'], 'release'))
    h.part('cs', CFG['analysis']['context'].encode())
    return h.hexdigest()


def chain_key(chain):
    """A chain without line numbers (Class#method:42, publisher and listener ids): code moved by an
    unrelated edit is the same finding, not a new one."""
    return re.sub(r'(#[\w$]+):\d+', r'\1', chain)


def finding_key(chain):
    """The finding a chain belongs to: its entry point without the source label, and the sink method.
    The same flow is printed once per granularity of the source (<0>, <0/warehouse>,
    <AddCartItemRequest.warehouse>) and once per path through the channels; these are one finding
    (experiments/precision.py counts findings the same way, with the sink line)."""
    entry = re.sub(r' <[^>]*>$', '', chain.split('  ')[0])
    sink = re.search(r'CWE-\d+ @ [\w$.]+#[\w$<>]+', chain).group(0)
    return f'{entry} => {sink}'


def group_findings(chains):
    """[{'key', 'chains'}], sorted by key; a finding's chains keep the order of `chains`."""
    groups = {}
    for c in chains:
        groups.setdefault(finding_key(c), []).append(c)
    return [{'key': k, 'chains': groups[k]} for k in sorted(groups)]


def summary_key(rkey, model):
    h = Hasher()
    h.part('run', rkey.encode())
    h.file('model', model)
    h.file('summarize', os.path.join(T, 'scripts', 'summarize.py'))
    return h.hexdigest()


def sh(*args, **kw):
    subprocess.run(['sh', *args], check=True, env=vkr_prepare.tool_env(CFG), **kw)


def compile_launcher():
    src = os.path.join(T, 'launcher', 'src')
    files = [os.path.join(d, f) for d, _, fs in os.walk(os.path.join(src, 'vkr')) for f in fs if f.endswith('.java')]
    subprocess.run([os.path.join(CFG['toolchain']['java_home'], 'bin', 'javac'), '--release', '17', '-encoding', 'UTF-8', '-nowarn',
                    '-cp', CFG['toolchain']['micans_jar'], '-d', os.path.join(T, 'launcher', 'classes'), *files],
                   check=True)


def prepare(svc, rkey, base, discovery):
    """Fresh workdir (classes and generated configs) for the commit whose run key is rkey."""
    w = os.path.join(WORK, svc)
    marker = os.path.join(w, '.runkey')
    if os.path.exists(marker) and open(marker).read() == rkey:
        return w
    shutil.rmtree(w, ignore_errors=True)
    vkr_prepare.layout(CFG, svc)
    vkr_prepare.taint_configs(CFG, svc, w, base, discovery)
    shutil.copyfile(discovery, os.path.join(w, 'discovery.json'))
    with open(marker, 'w') as f:
        f.write(rkey)
    return w


class ServiceFailed(Exception):
    """One service could not be analysed; the run goes on with the others and reports it."""

    def __init__(self, reason, log=None):
        super().__init__(reason)
        self.reason, self.log = reason, log


def failed_services_in(chain, failed):
    """Services of a chain that failed in this run (a chain names each node as ep|lis|db:<service>:...)."""
    return sorted({s for s in failed if re.search(rf'\b(?:ep|lis|db):{re.escape(s)}:', chain)})


def analyse(svc, rkey, flows_path, base, discovery):
    """Micans run on a fresh workdir; keeps only the flow lines of the log."""
    w = prepare(svc, rkey, base, discovery)
    log = os.path.join(w, 'run.log')
    sh(os.path.join(T, 'scripts', 'run_taint.sh'), svc, w, os.path.join(w, 'taint-db.yml'),
       os.path.join(w, 'out'), log, CFG['analysis']['context'])
    with open(log, encoding='utf-8', errors='replace') as f:
        lines = f.read().splitlines()
    if not lines or not lines[-1].startswith('exit=0'):
        raise ServiceFailed('analysis failed', log)
    flows = [l for l in lines if l.startswith(FLOW_PREFIXES)]
    with open(flows_path + '.tmp', 'w', encoding='utf-8') as f:
        f.write('\n'.join(flows) + '\n')
    os.replace(flows_path + '.tmp', flows_path)


def summarize(svc, rkey, flows_path, model, out, base, discovery):
    w = prepare(svc, rkey, base, discovery)  # flows may come from the cache while the workdir holds another commit
    env = {**vkr_prepare.tool_env(CFG), 'VKR_DISCOVERY': os.path.join(w, 'discovery.json')}
    subprocess.run([sys.executable, '-I', os.path.join(T, 'scripts', 'summarize.py'), 'summary', svc, flows_path,
                    model, out + '.tmp', os.path.join(w, 'app'), os.path.join(w, 'common')],
                   check=True, stdout=subprocess.DEVNULL, env=env)
    os.replace(out + '.tmp', out)


def extract_model(out):
    """Channel model of project.root by channel-extractor (reads sources only, takes seconds)."""
    if not os.path.isfile(EXTRACTOR_JAR):
        sys.exit(f'vkr-taint: channel-extractor jar not found: {EXTRACTOR_JAR} '
                 f'(build analysis/channel-extractor, set VKR_CHANNEL_EXTRACTOR, or pass --model)')
    args = [os.path.join(CFG['toolchain']['java_home'], 'bin', 'java'), '-jar', EXTRACTOR_JAR, CFG['root'],
            '--out', out]
    if CFG['gateway_config']:
        args += ['--gateway-config', CFG['gateway_config']]
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)
    return out


def main():
    global CFG
    ap = argparse.ArgumentParser()
    ap.add_argument('--config', help='vkr-taint.yml (default: $VKR_CONFIG or ./vkr-taint.yml)')
    ap.add_argument('--services', help='subset of the configured services')
    ap.add_argument('--model', help='channel model (default: extracted from project.root)')
    ap.add_argument('--baseline', help='report to compare with (default: gate.baseline when it exists)')
    ap.add_argument('--out', default=os.path.join(WORK, 'report.json'))
    g = ap.add_mutually_exclusive_group()
    g.add_argument('--gate', action='store_true', help='exit 1 on new chains (default: gate.fail-on)')
    g.add_argument('--no-gate', action='store_true')
    a = ap.parse_args()
    try:
        CFG = vkr_config.load(a.config, require=('java', 'micans', 'artifacts'))
    except vkr_config.ConfigError as e:
        sys.exit(f'vkr-taint: {e}')
    for n in CFG['notes']:
        print(f'note: {n}')
    services = a.services.split(',') if a.services else [m['name'] for m in CFG['services']]
    try:
        for svc in services:
            vkr_prepare.module(CFG, svc)
    except vkr_config.ConfigError as e:
        sys.exit(f'vkr-taint: {e}')
    if a.baseline is None and CFG['gate']['baseline'] and os.path.isfile(CFG['gate']['baseline']):
        a.baseline = CFG['gate']['baseline']
    gate = a.gate or (CFG['gate']['fail_on'] == 'new-chains' and not a.no_gate)
    start = time.time()
    os.makedirs(WORK, exist_ok=True)
    model = a.model or extract_model(os.path.join(WORK, 'channels.json'))
    model_json = json.load(open(model, encoding='utf-8'))
    compile_launcher()
    report = {'services': {}}
    summaries, failed = [], []
    for svc in services:
        t0 = time.time()
        try:
            base, discovery = vkr_prepare.base_config(CFG, model_json, svc, os.path.join(WORK, 'gen', svc))
            rkey = run_key(svc, base, discovery)
            d = os.path.join(CACHE, svc, rkey[:16])
            os.makedirs(d, exist_ok=True)
            flows = os.path.join(d, 'flows.txt')
            hit_run = os.path.exists(flows)
            if not hit_run:
                analyse(svc, rkey, flows, base, discovery)
            skey = summary_key(rkey, model)
            summary = os.path.join(d, f'summary-{skey[:16]}.json')
            hit_summary = os.path.exists(summary)
            if not hit_summary:
                summarize(svc, rkey, flows, model, summary, base, discovery)
        except (ServiceFailed, subprocess.CalledProcessError, OSError, ValueError) as e:
            # a failed service has no summary: compose marks the channels into it "(no summary yet)"
            reason = e.reason if isinstance(e, ServiceFailed) else f'{type(e).__name__}: {e}'
            log = getattr(e, 'log', None)
            failed.append(svc)
            report['services'][svc] = {'failed': reason, 'log': log, 'seconds': round(time.time() - t0, 1)}
            print(f'{svc}: FAILED ({reason})' + (f', see {log}' if log else '') + f', {time.time() - t0:.0f} s',
                  flush=True)
            continue
        summaries.append(summary)
        report['services'][svc] = {'runKey': rkey[:16], 'runCached': hit_run, 'summaryCached': hit_summary,
                                   'seconds': round(time.time() - t0, 1)}
        print(f'{svc}: run {"hit" if hit_run else "miss"}, summary {"hit" if hit_summary else "miss"}, '
              f'{time.time() - t0:.0f} s', flush=True)
    report['failed'] = failed
    composed = subprocess.run([sys.executable, '-I', os.path.join(T, 'scripts', 'summarize.py'), 'compose',
                               model, *summaries], check=True, capture_output=True, text=True,
                              env=vkr_prepare.tool_env(CFG)).stdout
    chains = sorted({l for l in composed.splitlines() if 'CWE-' in l})
    report['chains'] = chains
    findings = group_findings(chains)
    report['findings'] = findings
    report['seconds'] = round(time.time() - start, 1)
    new = removed = new_findings = removed_findings = []
    if a.baseline:
        old = json.load(open(a.baseline, encoding='utf-8'))['chains']
        old_keys, cur_keys = {chain_key(c) for c in old}, {chain_key(c) for c in chains}
        new = [c for c in chains if chain_key(c) not in old_keys]
        gone = [c for c in old if chain_key(c) not in cur_keys]
        # a baseline chain through a service that failed now was not re-checked, it is not removed
        removed = [c for c in gone if not failed_services_in(c, failed)]
        report['new'], report['removed'] = new, removed
        report['unchecked'] = [c for c in gone if failed_services_in(c, failed)]
        # the gate counts findings: another path or source label of a known finding is not a new one
        old_findings = {finding_key(c) for c in old}
        cur_findings = {f['key'] for f in findings}
        new_findings = [f['key'] for f in findings if f['key'] not in old_findings]
        removed_findings = sorted({finding_key(c) for c in removed} - cur_findings)
        report['newFindings'], report['removedFindings'] = new_findings, removed_findings
    with open(a.out, 'w', encoding='utf-8') as f:
        json.dump(report, f, indent=1)
    crossing = sum('-->' in c for c in chains)
    print(f'{len(findings)} findings, {len(chains)} chains to a sink ({crossing} across a channel)'
          + (f'; {len(new_findings)} new and {len(removed_findings)} removed findings '
             f'({len(new)} new, {len(removed)} removed chains)' if a.baseline else '')
          + f'; {report["seconds"]:.0f} s total -> {a.out}')
    for k in new_findings:
        print('NEW', k)
    if failed:
        print(f'{len(failed)} of {len(services)} services FAILED, their chains are missing: {", ".join(failed)}')
    if gate and new_findings:
        sys.exit(1)
    if gate and failed:
        sys.exit(2)  # an incomplete analysis must not pass the gate


if __name__ == '__main__':
    main()
