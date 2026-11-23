"""Lays out the classes of a service workdir for Micans.

The project's shared modules (env VKR_SHARED_JARS: comma-separated artifact names, e.g. common-lib;
vkr_config.py discovers them) are extracted to <workdir>/common (analysed as application code). The other library jars
stay as jars and are listed in <workdir>/libjars.txt: writing their ~50k classes out as files took
~7 min per service on Windows, reading them from the jars takes seconds. A library jar that has
classes newer than Micans' ASM 9.4 can read is extracted to <workdir>/lib instead.
The libraries are those of a Spring Boot jar (BOOT-INF/lib) or of a war (WEB-INF/lib). A servlet
application whose libraries lack the Servlet API (scope provided: the container supplies it) gets the
analysis-only stubs (env VKR_SERVLET_STUBS: <dir with javax.jar and jakarta.jar>, built by prepare.sh).
usage: prep_micans.py <workdir>"""
import sys, os, zipfile, struct

# on Windows the \\?\ prefix lifts the 260-char path limit
PFX = '\\\\?\\' if os.name == 'nt' else ''
W = PFX + os.path.abspath(sys.argv[1])
libdirs = [os.path.join(W, 'bootjar', d, 'lib') for d in ('BOOT-INF', 'WEB-INF')]
common = os.path.join(W, 'common')
lib = os.path.join(W, 'lib')
os.makedirs(common, exist_ok=True)
os.makedirs(lib, exist_ok=True)


SHARED = [a for a in os.environ.get('VKR_SHARED_JARS', '').split(',') if a]


def shared(jar):
    return any(jar == a + '.jar' or (jar.startswith(a + '-') and jar[len(a) + 1:len(a) + 2].isdigit())
               for a in SHARED)


def classes(z):
    return [n for n in z.namelist()
            if n.endswith('.class') and not n.startswith('META-INF/') and not n.endswith('module-info.class')]


def too_new(data):
    return len(data) >= 8 and struct.unpack('>H', data[6:8])[0] > 64


jars = []
found = set()  # class entries of the library jars, for the Servlet API check
for jar, path in sorted((j, os.path.join(d, j)) for d in libdirs if os.path.isdir(d) for j in os.listdir(d)):
    with zipfile.ZipFile(path) as z:
        names = classes(z)
        found.update(n for n in names if n.endswith('/http/HttpServlet.class'))
        if not shared(jar) and not any(too_new(z.open(n).read(8)) for n in names):
            jars.append(path[len(PFX):])  # without \\?\: the list is read by Java
            continue
        dest = common if shared(jar) else lib
        for n in names:
            out = os.path.join(dest, *n.split('/'))
            if os.path.exists(out):
                continue
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, 'wb') as f:
                f.write(z.read(n))


def references(prefix):
    """True when an application class names the type (constant pool entry), e.g. its superclass."""
    needle = prefix.encode()
    for root, _, files in os.walk(os.path.join(W, 'app')):
        for fn in files:
            if fn.endswith('.class'):
                with open(os.path.join(root, fn), 'rb') as f:
                    if needle in f.read():
                        return True
    return False


stubs = os.environ.get('VKR_SERVLET_STUBS')
for ns in ('javax', 'jakarta'):
    api = f'{ns}/servlet/http/HttpServlet'
    if stubs and api + '.class' not in found and references(api):
        jars.append(os.path.join(stubs, ns + '.jar'))
        print(f'{ns}.servlet API not packaged (scope provided): analysis stubs added')
with open(os.path.join(W, 'libjars.txt'), 'w', encoding='utf-8') as f:
    f.write(''.join(j + '\n' for j in jars))

# Micans bundles ASM 9.4 (max class version 64 = Java 20); downgrade newer headers
stats = {'jars': len(jars)}
for d in ('app', 'common', 'lib'):
    patched = total = 0
    for root, _, files in os.walk(os.path.join(W, d)):
        for fn in files:
            if not fn.endswith('.class'):
                continue
            p = os.path.join(root, fn)
            total += 1
            with open(p, 'r+b') as f:
                h = f.read(8)
                if too_new(h):
                    f.seek(6)
                    f.write(struct.pack('>H', 61))
                    patched += 1
    stats[d] = (total, patched)
print(stats)
