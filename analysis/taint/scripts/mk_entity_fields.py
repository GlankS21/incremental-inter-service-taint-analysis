"""Field sources for the persistent state of a service: every reference-typed field of the classes
in its entity packages (entities and embeddables). A load of such a field reads what an earlier
request may have stored, so its taint label 'Entity.field' is the receiving end of a database
channel (the sending end is a StoreFlow reported by vkr.plugin.SpringDataModel).
usage: mk_entity_fields.py <app classes> <entity package>[,<package>...] <out.yml>
(the packages are those of the @Entity classes, found by gen_taint_config.py)"""
import os
import re
import subprocess
import sys

if not os.environ.get('JAVA_HOME'):
    sys.exit(f'{os.path.basename(__file__)}: JAVA_HOME is not set (a JDK home, for javap)')
JAVAP = os.path.join(os.environ['JAVA_HOME'], 'bin', 'javap')
SKIP_TYPES = re.compile(r'^(java\.util\.(List|Set|Map|Collection)|byte\[\])')

# Fields that never hold unvalidated text (TCI-4) are no field sources: they are listed with their
# proof under `exclusions:` of the project's overrides file (for the testbed
# config/ecommerce.vkr-taint-overrides.yml) and removed from the merged config by vkr_prepare.py.

app, pkgs, out = sys.argv[1:4]
lines = ['  # entity fields: what a request reads back from the database']
for pkg, f in [(p, f) for p in pkgs.split(',') if p for f in sorted(os.listdir(os.path.join(app, *p.split('.'))))]:
    if not f.endswith('.class') or '$' in f:
        continue
    cls = f'{pkg}.{f[:-6]}'
    text = subprocess.run([JAVAP, '-p', '-cp', app, cls], capture_output=True, text=True).stdout
    if re.search(r'\bfinal class \S+ extends java\.lang\.Enum|\benum\b', text.split('{')[0]):
        continue
    for typ, name in re.findall(r'^\s+(?:private|protected) (?!static)(?:final )?([\w.$\[\]]+) (\w+);', text, re.M):
        if '.' not in typ or SKIP_TYPES.match(typ) or typ.startswith(pkg):
            continue  # primitives carry no taint object; collections and nested entities are walked into
        lines.append(f'  - {{ kind: field, field: "<{cls}: {typ} {name}>" }}')
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print(len(lines) - 1, 'entity field sources')
