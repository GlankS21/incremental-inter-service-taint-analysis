"""Compares generated base taint configs with hand-written ones entry by entry (acceptance check of
gen_taint_config.py). Method patterns of the hand-written configs that name an application class
(e.g. <...SagaReplyConsumer: void on*(*)>) are expanded against the bytecode first, so a wildcard and
the concrete signatures it matches compare equal.

usage: compare_configs.py [--config vkr-taint.yml] --generated DIR --handwritten 'DIR/taint-{short}.yml'
       (DIR holds taint-<service>.yml as written by gen_taint_config.py --out-dir)
"""
import argparse
import fnmatch
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_taint_config as gen  # noqa: E402
import vkr_config  # noqa: E402
import vkr_prepare  # noqa: E402

SECTIONS = ('sources', 'sinks', 'transfers')
PATTERN = re.compile(r'^<([\w.$]+): (\S+) ([\w*<>$]+)\((.*)\)>$')


def entries(path):
    out = {s: [] for s in SECTIONS}
    sec = None
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if line and not line[0].isspace() and not line.startswith('#') and line.endswith(':'):
            sec = line[:-1]
        e = vkr_prepare.parse_entry(line)
        if e and sec in out:
            out[sec].append(e)
    return out


def expand(entry, svc):
    """Concrete signatures for a wildcard method of an application class, else the entry itself."""
    m = PATTERN.match(entry.get('method') or '')
    if not m or '*' not in entry['method'] or m.group(1) not in svc.app_classes + svc.shared_classes:
        return [entry]
    cls, ret, name, params = m.groups()
    info = svc.cls(cls)
    out = []
    for meth in (info.methods if info else []):
        if not fnmatch.fnmatchcase(meth[0], name) or (ret != '*' and ret != meth[2]):
            continue
        if params not in ('*', '*{0+}') and ','.join(meth[1]) != params:
            continue
        out.append({**entry, 'method': gen.tai_sig(cls, meth)})
    return out or [entry]


def key(e):
    return vkr_prepare.norm(e)


def compare(hand, generated, svc):
    rows = {}
    for sec in SECTIONS:
        h = {key(x): x for e in hand[sec] for x in expand(e, svc)}
        g = {key(e): e for e in generated[sec]}
        rows[sec] = (len(h), len(g), [h[k] for k in h if k in g], [g[k] for k in g if k not in h],
                     [h[k] for k in h if k not in g])
    return rows


def short(e):
    return ', '.join(f'{k}={v}' for k, v in e.items() if k != 'kind' or v != 'param')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--config')
    ap.add_argument('--generated', required=True)
    ap.add_argument('--handwritten', required=True, help="path template with {service} or {short}")
    a = ap.parse_args()
    try:
        cfg = vkr_config.load(a.config, require=('java', 'artifacts'))
    except vkr_config.ConfigError as e:
        sys.exit(f'vkr-taint: {e}')
    print(f'{"service":18} {"section":10} {"hand":>5} {"gen":>5} {"same":>5} {"extra":>6} {"missing":>8}')
    details = []
    for m in cfg['services']:
        name = m['name']
        hpath = a.handwritten.replace('{service}', name).replace('{short}', vkr_config.short_name(name))
        gpath = os.path.join(a.generated, f'taint-{name}.yml')
        if not (os.path.isfile(hpath) and os.path.isfile(gpath)):
            print(f'{name}: missing {hpath if not os.path.isfile(hpath) else gpath}')
            continue
        svc = gen.Service(cfg, m, cfg['toolchain']['java_home'])
        rows = compare(entries(hpath), entries(gpath), svc)
        for sec, (nh, ng, same, extra, missing) in rows.items():
            print(f'{name:18} {sec:10} {nh:5} {ng:5} {len(same):5} {len(extra):6} {len(missing):8}')
            details += [(name, sec, '+', short(e)) for e in extra] + [(name, sec, '-', short(e)) for e in missing]
    print('\n+ = generated only (extra), - = hand-written only (missing); wildcards expanded against the bytecode')
    for name, sec, sign, text in details:
        print(f'{sign} {name} {sec}: {text}')


if __name__ == '__main__':
    main()
