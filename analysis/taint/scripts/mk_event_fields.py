"""Writes the shared field-level channel config for common-lib event records:
 - every component of an event record is a field source (receiver side: one taint label per field),
 - every constructor argument is a sink (sender side: which field of the payload is tainted).
usage: mk_event_fields.py <common classes dir> <out sources.yml> <out sinks.yml>
"""
import os
import re
import subprocess
import sys

JAVAP = os.path.join(os.environ.get('JAVA_HOME', 'C:/Program Files/Java/jdk-24'), 'bin', 'javap')

# Event record components that never hold unvalidated text (TCI-4) are no field sources. Each
# entry must carry a proof that EVERY construction site of the record passes a validated value:
# - ProductCreatedEvent.sku: the only production construction is ProductServiceImpl.java:142-143
#   with saved.getSku(); Product.sku is written only at ProductServiceImpl.java:135 from
#   request.sku() of the @Valid-bound ProductRequest (ProductController.java:112) whose sku
#   carries @Pattern(ValidationPatterns.SKU) = ^[A-Z0-9][A-Z0-9-]{2,63}$ (ProductRequest.java:22).
#   (The constructions in the tests are not part of the analysed build.)
EXCLUDED_FIELDS = {
    'com.shop.common.event.ProductCreatedEvent': ['sku'],
}

common, out_sources, out_sinks = sys.argv[1:4]
pkg = os.path.join(common, 'com', 'shop', 'common', 'event')
sources, sinks = [], []
for f in sorted(os.listdir(pkg)):
    if not f.endswith('.class') or '$' in f:
        continue
    cls = 'com.shop.common.event.' + f[:-6]
    out = subprocess.run([JAVAP, '-p', '-cp', common, cls], capture_output=True, text=True).stdout
    if 'extends java.lang.Record' not in out:
        continue
    fields = re.findall(r'private final (\S+) (\w+);', out)
    for ftype, name in fields:
        # list elements are covered by their own record; excluded components are validated everywhere
        if not ftype.startswith('java.util.List') and name not in EXCLUDED_FIELDS.get(cls, []):
            sources.append(f'  - {{ kind: field, field: "<{cls}: {ftype} {name}>" }}')
    erased = ','.join(re.sub(r'<.*>', '', t) for t, _ in fields)
    for i, (ftype, name) in enumerate(fields):
        sinks.append(f'  - {{ method: "<{cls}: void <init>({erased})>", index: {i} }}  # {name}')
open(out_sources, 'w', encoding='utf-8').write(
    '  # event record components: receiver-side taint label per field\n' + '\n'.join(sources) + '\n')
open(out_sinks, 'w', encoding='utf-8').write(
    '  # event constructors: sender-side, which payload field is tainted\n' + '\n'.join(sinks) + '\n')
print(len(sources), 'field sources,', len(sinks), 'constructor sinks')
