"""Prepares the workdir of a service for its Micans run: class layout (prepare.sh) and the taint
config chain

  base       generated (gen_taint_config.py) or hand-written (taint.base-config)   taint-base.yml
  coarse     + request-DTO field sources       (mk_dto_fields.py, discovered DTO packages)
  field      listener payloads -> event fields (mk_event_fields.py, mk_field_config.py)
  db         + entity field sources, call-site summaries (mk_entity_fields.py, mk_db_config.py)
  final      + overrides merged last, exclusions removed                           taint-db.yml

usage: vkr_prepare.py [--config vkr-taint.yml] --model channels.json [--services a,b] [--configs-only]
env:   VKR_WORK (default work/)
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_taint_config  # noqa: E402
import vkr_config  # noqa: E402
import vkr_yaml  # noqa: E402

T = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(T, 'scripts')
SECTIONS = ('sources', 'sinks', 'transfers', 'sanitizers')


def work_dir():
    return os.environ.get('VKR_WORK', os.path.join(T, 'work'))


def tool_env(cfg):
    env = dict(os.environ)
    if cfg['toolchain']['java_home']:
        env['JAVA_HOME'] = cfg['toolchain']['java_home']
    if cfg['toolchain']['micans_jar']:
        env['MICANS_JAR'] = cfg['toolchain']['micans_jar']
    env['VKR_SHARED_JARS'] = ','.join(m['artifact'] for m in cfg['shared'])
    env['VKR_HEAP'] = cfg['analysis']['heap']
    env['VKR_WORLD_CACHE'] = cfg['analysis']['world_cache']
    if 'cwe-79' in cfg['taint']['sinks']:
        env['VKR_RESPONSE_SINKS'] = 'true'  # vkr.plugin.ResponseBodySink
    else:
        env.pop('VKR_RESPONSE_SINKS', None)
    env['PYTHONUTF8'] = '1'
    env['VKR_PYTHON'] = sys.executable  # the shell scripts run Python helpers with this interpreter
    return env


def module(cfg, name):
    for m in cfg['services']:
        if m['name'] == name:
            return m
    raise vkr_config.ConfigError(f'service {name} is not in the configuration '
                                 f'(known: {", ".join(m["name"] for m in cfg["services"])})')


def base_config(cfg, model, name, out_dir):
    """Writes taint-base.yml and discovery.json of a service into out_dir; returns their paths.
    Cheap (javap on a few classes), so it runs before the run key is computed."""
    os.makedirs(out_dir, exist_ok=True)
    text, disc, _ = gen_taint_config.generate(cfg, model, module(cfg, name), cfg['toolchain']['java_home'])
    hand = vkr_config.base_config_path(cfg, name)
    if hand:
        if not os.path.isfile(hand):
            raise vkr_config.ConfigError(f'taint.base-config: {hand} not found for {name}')
        text = open(hand, encoding='utf-8').read()
        disc['baseConfig'] = hand
    base = os.path.join(out_dir, 'taint-base.yml')
    with open(base, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    dpath = os.path.join(out_dir, 'discovery.json')
    with open(dpath, 'w', encoding='utf-8') as f:
        json.dump(disc, f, indent=1)
    return base, dpath


# ------------------------------------------------------------------ entries in a config text

def parse_entry(line):
    s = line.strip()
    if not s.startswith('- {'):
        return None
    try:
        return vkr_yaml._flow(vkr_yaml._strip_comment(s[1:].strip()))
    except vkr_yaml.YamlError:
        return None


def norm(e):
    return tuple(sorted((k, str(v)) for k, v in e.items()))


def sections(lines):
    """{section: (header index, end index)} of the top-level keys of a config text."""
    out, cur = {}, None
    for i, l in enumerate(lines):
        if l and not l[0].isspace() and not l.startswith('#') and l.rstrip().endswith(':'):
            if cur:
                out[cur] = (out[cur][0], i)
            cur = l.rstrip()[:-1]
            out[cur] = (i, len(lines))
    return out


def merge(text, additions, label):
    """Appends the entries of additions {section: [entry]} not yet in the text to their sections."""
    lines = text.rstrip('\n').split('\n')
    for sec in SECTIONS:
        add = additions.get(sec) or []
        if not add:
            continue
        secs = sections(lines)
        if sec in secs:
            start, end = secs[sec]
            have = {norm(e) for e in map(parse_entry, lines[start + 1:end]) if e}
        else:
            lines += ['', f'{sec}:']
            start, end = len(lines) - 1, len(lines)
            have = set()
        new = []
        for e in add:
            if norm(e) not in have:
                have.add(norm(e))
                new.append(f'  - {vkr_yaml.dump_entry(e)}')
        if not new:
            continue
        while end > start + 1 and not lines[end - 1].strip():
            end -= 1
        lines[end:end] = [f'  # {label}'] + new
    return '\n'.join(lines) + '\n'


def exclude(text, exclusions):
    """Removes the entries naming an excluded field (kind: field) or method; returns (text, removed)."""
    fields = {e['field'] for e in exclusions if e.get('field')}
    methods = {e['method'] for e in exclusions if e.get('method')}
    out, removed = [], []
    for line in text.split('\n'):
        e = parse_entry(line)
        if e and (e.get('field') in fields or e.get('method') in methods):
            removed.append(line.strip())
            continue
        out.append(line)
    return '\n'.join(out), removed


def overrides_for(cfg, name):
    o = cfg['overrides']
    add = {s: list(o['common'].get(s) or []) + list((o['services'].get(name) or {}).get(s) or [])
           for s in SECTIONS}
    return add, o['exclusions'], [h['method'] for h in o['call_site_helpers']]


# ------------------------------------------------------------------ the chain

def py(env, *args, cwd=None):
    subprocess.run([sys.executable, '-I', *args], check=True, env=env, cwd=cwd, stdout=subprocess.DEVNULL)


def taint_configs(cfg, name, w, base, discovery):
    """Builds w/taint-db.yml from the base config; w holds app/ and common/ (prepare.sh)."""
    env = tool_env(cfg)
    disc = json.load(open(discovery, encoding='utf-8'))
    s = lambda f: os.path.join(SCRIPTS, f)  # noqa: E731
    shutil.copyfile(base, os.path.join(w, 'taint-base.yml'))
    base_text = open(base, encoding='utf-8').read()
    coarse = os.path.join(w, 'taint-coarse.yml')
    # a hand-written base asks for the generated DTO fields with a comment naming mk_dto_fields
    dirs = os.pathsep.join(d for d in (os.path.join(w, 'app'), os.path.join(w, 'common')) if os.path.isdir(d))
    if disc['dtoPackages'] and 'kind: field' not in base_text and (
            'baseConfig' not in disc or 'mk_dto_fields' in base_text):
        py(env, s('mk_dto_fields.py'), dirs, ','.join(disc['dtoPackages']), base, coarse)
    else:
        shutil.copyfile(base, coarse)
    if disc['eventPackages']:
        py(env, s('mk_event_fields.py'), dirs, ','.join(disc['eventPackages']),
           os.path.join(w, 'event-sources.yml'), os.path.join(w, 'event-sinks.yml'))
    else:
        for f, c in (('event-sources.yml', 'event record components'), ('event-sinks.yml', 'event constructors')):
            with open(os.path.join(w, f), 'w', encoding='utf-8') as fh:
                fh.write(f'  # {c}: no event types on the channels of this service\n')
    py(env, s('mk_field_config.py'), coarse, os.path.join(w, 'taint-field.yml'), cwd=w)
    entity = os.path.join(w, 'entity-sources.yml')
    if disc['entityPackages']:
        py(env, s('mk_entity_fields.py'), dirs, ','.join(disc['entityPackages']), entity)
    else:
        with open(entity, 'w', encoding='utf-8') as fh:
            fh.write('  # entity fields: no @Entity class in this service\n')
    add, exclusions, helpers = overrides_for(cfg, name)
    hpath = os.path.join(w, 'call-site-helpers.json')
    with open(hpath, 'w', encoding='utf-8') as fh:
        json.dump(helpers, fh, indent=1)
    py(env, s('mk_db_config.py'), os.path.join(w, 'taint-field.yml'), entity, os.path.join(w, 'taint-dbgen.yml'), hpath)
    text = open(os.path.join(w, 'taint-dbgen.yml'), encoding='utf-8').read()
    text = merge(text, add, f'overrides ({os.path.basename(cfg["taint"]["overrides"] or "")})')
    text, removed = exclude(text, exclusions)
    with open(os.path.join(w, 'taint-db.yml'), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text)
    return os.path.join(w, 'taint-db.yml'), removed


def layout(cfg, name):
    m = module(cfg, name)
    subprocess.run(['sh', os.path.join(SCRIPTS, 'prepare.sh'), name, m['classes'], m['jar']], check=True,
                   env=tool_env(cfg))
    return os.path.join(work_dir(), name)


def main():
    ap = argparse.ArgumentParser(description='prepares the workdirs (classes + taint configs) of the services')
    ap.add_argument('--config')
    ap.add_argument('--model', required=True, help='channel model of channel-extractor')
    ap.add_argument('--services')
    a = ap.parse_args()
    try:
        cfg = vkr_config.load(a.config, require=('java', 'artifacts'))
    except vkr_config.ConfigError as e:
        sys.exit(f'vkr-taint: {e}')
    model = json.load(open(a.model, encoding='utf-8'))
    for name in (a.services.split(',') if a.services else [m['name'] for m in cfg['services']]):
        w = layout(cfg, name)
        base, disc = base_config(cfg, model, name, os.path.join(w, 'gen'))
        out, removed = taint_configs(cfg, name, w, base, disc)
        print(f'{name}: {out} ({len(removed)} entries excluded)')


if __name__ == '__main__':
    main()
