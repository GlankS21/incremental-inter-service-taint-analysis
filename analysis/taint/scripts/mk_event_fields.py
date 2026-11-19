"""Writes the field-level channel config for the event records of the given packages:
 - every component of an event record is a field source (receiver side: one taint label per field),
 - every constructor argument is a sink (sender side: which field of the payload is tainted).
usage: mk_event_fields.py <classes dir>[<os.pathsep><dir>...] <event package>[,<package>...] <out sources.yml> <out sinks.yml>
(the packages are those of the channel payload types, found by gen_taint_config.py)
"""
import os
import re
import subprocess
import sys

if not os.environ.get('JAVA_HOME'):
    sys.exit(f'{os.path.basename(__file__)}: JAVA_HOME is not set (a JDK home, for javap)')
JAVAP = os.path.join(os.environ['JAVA_HOME'], 'bin', 'javap')

# Event record components that never hold unvalidated text (TCI-4) are no field sources: they are
# listed with their proof under `exclusions:` of the project's overrides file and removed from the
# merged config by vkr_prepare.py.

dirs, pkgs, out_sources, out_sinks = sys.argv[1:5]
dirs = [d for d in dirs.split(os.pathsep) if d]
classes = sorted({f'{p}.{f[:-6]}' for d in dirs for p in pkgs.split(',') if p
                  if os.path.isdir(os.path.join(d, *p.split('.')))
                  for f in os.listdir(os.path.join(d, *p.split('.'))) if f.endswith('.class') and '$' not in f})
sources, sinks = [], []
for cls in classes:
    out = subprocess.run([JAVAP, '-p', '-cp', os.pathsep.join(dirs), cls], capture_output=True, text=True).stdout
    if 'extends java.lang.Record' not in out:
        continue
    fields = re.findall(r'private final (\S+) (\w+);', out)
    for ftype, name in fields:
        # list elements are covered by their own record; excluded components are validated everywhere
        if not ftype.startswith('java.util.List'):
            sources.append(f'  - {{ kind: field, field: "<{cls}: {ftype} {name}>" }}')
    erased = ','.join(re.sub(r'<.*>', '', t) for t, _ in fields)
    for i, (ftype, name) in enumerate(fields):
        sinks.append(f'  - {{ method: "<{cls}: void <init>({erased})>", index: {i} }}  # {name}')
open(out_sources, 'w', encoding='utf-8').write(
    '  # event record components: receiver-side taint label per field\n' + '\n'.join(sources) + '\n')
open(out_sinks, 'w', encoding='utf-8').write(
    '  # event constructors: sender-side, which payload field is tainted\n' + '\n'.join(sinks) + '\n')
print(len(sources), 'field sources,', len(sinks), 'constructor sinks')
