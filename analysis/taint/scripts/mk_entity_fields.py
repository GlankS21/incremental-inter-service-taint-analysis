"""Field sources for the persistent state of a service: every reference-typed field of the classes
in its entity package (entities and embeddables). A load of such a field reads what an earlier
request may have stored, so its taint label 'Entity.field' is the receiving end of a database
channel (the sending end is a StoreFlow reported by vkr.plugin.SpringDataModel).
usage: mk_entity_fields.py <app classes> <entity package> <out.yml>"""
import os
import re
import subprocess
import sys

JAVAP = os.path.join(os.environ.get('JAVA_HOME', 'C:/Program Files/Java/jdk-24'), 'bin', 'javap')
SKIP_TYPES = re.compile(r'^(java\.util\.(List|Set|Map|Collection)|byte\[\])')

# Fields that never hold unvalidated text (TCI-4) are no field sources. Each entry must carry a
# proof that EVERY writer of the field passes through validation:
# - com.shop.order.entity.Coupon.code: written only at CouponServiceImpl.java:62,82
#   (coupon.setCode(code)) from Coupon.normalizeCode(request.code()) at CouponServiceImpl.java:57,78;
#   CouponRequest.code carries @Pattern(Coupon.CODE_PATTERN) = ^[A-Za-z0-9_-]{3,30}$ and both
#   controller methods bind the request @Valid (AdminCouponController.java:66,73).
# - com.shop.order.entity.Order.couponCode: written only in Order.applyCoupon (Order.java:163;
#   Order.java:147 sets it null); its sole caller is OrderServiceImpl.java:110, passing
#   applied.getCode() of a Coupon loaded from the repository, whose code is validated as above.
# Coupon.description must stay a source: the V05 TP chain reads it back from the database
# (--DB Coupon [Coupon.description]).
EXCLUDED_FIELDS = {
    'com.shop.order.entity.Coupon': ['code'],
    'com.shop.order.entity.Order': ['couponCode'],
}

app, pkg, out = sys.argv[1:4]
lines = ['  # entity fields: what a request reads back from the database']
pkg_dir = os.path.join(app, *pkg.split('.'))
for f in sorted(os.listdir(pkg_dir)):
    if not f.endswith('.class') or '$' in f:
        continue
    cls = f'{pkg}.{f[:-6]}'
    text = subprocess.run([JAVAP, '-p', '-cp', app, cls], capture_output=True, text=True).stdout
    if re.search(r'\bfinal class \S+ extends java\.lang\.Enum|\benum\b', text.split('{')[0]):
        continue
    for typ, name in re.findall(r'^\s+(?:private|protected) (?!static)(?:final )?([\w.$\[\]]+) (\w+);', text, re.M):
        if '.' not in typ or SKIP_TYPES.match(typ) or typ.startswith(pkg):
            continue  # primitives carry no taint object; collections and nested entities are walked into
        if name in EXCLUDED_FIELDS.get(cls, []):
            continue
        lines.append(f'  - {{ kind: field, field: "<{cls}: {typ} {name}>" }}')
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print(len(lines) - 1, 'entity field sources')
