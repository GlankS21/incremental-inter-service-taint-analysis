"""Field sources for the request DTOs of a service: every non-list `private final` field of the
classes in its request-DTO packages, nested ones included (e.g. CreateOrderRequest.items[*].productId).
Inserted into the service's base config before `sinks:`.
usage: mk_dto_fields.py <app classes> <dto package>[,<package>...] <config.yml> <out.yml>
(the packages come from the discovery of gen_taint_config.py: those of the request-body types)"""
import os
import re
import subprocess
import sys

if not os.environ.get('JAVA_HOME'):
    sys.exit(f'{os.path.basename(__file__)}: JAVA_HOME is not set (a JDK home, for javap)')
JAVAP = os.path.join(os.environ['JAVA_HOME'], 'bin', 'javap')

app, pkgs, src, out = sys.argv[1:5]
lines = ['  # request DTOs are only built by deserialising the HTTP body, so every scalar field of a',
         '  # nested DTO (e.g. CreateOrderRequest.items[*].productId) is attacker-controlled']
classes = [f'{pkg}.{f[:-6]}' for pkg in pkgs.split(',') if pkg
           for f in sorted(os.listdir(os.path.join(app, *pkg.split('.')))) if f.endswith('.class')]
for cls in classes:
    text = subprocess.run([JAVAP, '-p', '-cp', app, cls], capture_output=True, text=True).stdout
    for typ, name in re.findall(r'^\s*private final (\S+) ([a-zA-Z0-9]+);', text, re.M):
        if 'java.util.List' not in typ:
            lines.append(f'  - {{ kind: field, field: "<{cls}: {typ} {name}>" }}')
s = open(src, encoding='utf-8').read()
assert '\nsinks:\n' in s and 'kind: field' not in s
s = s.replace('\nsinks:\n', '\n' + '\n'.join(lines) + '\n\nsinks:\n', 1)
open(out, 'w', encoding='utf-8').write(s)
print(len(lines) - 2, 'request-DTO field sources')
