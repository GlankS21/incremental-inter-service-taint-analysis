"""Loads and validates vkr-taint.yml, the per-project configuration of the vkr-taint tool.

    version: 1
    project:   { root: ., gateway-config: auto }
    services:  auto            # or a list of names / {name, module, jar, classes}
    shared:    auto            # or a list of names / {name, module, artifact}
    taint:
      sinks: [cwe-89, cwe-117] # built-in catalogues (gen_taint_config.CATALOGUES: also cwe-79,
                               # cwe-918, cwe-1336, cwe-78, cwe-22) or Tai-e yml files
      sources: auto            # externally reachable endpoints + listeners of the channel model
      discovery: annotations   # request bodies, @Entity classes and channel payloads, not package names
      packages: { dto: [..], entity: [..], event: [..] }   # optional override of the discovery
      overrides: vkr-taint-overrides.yml
      base-config: null        # optional: hand-written per-service base config, {service}/{short}
    analysis:  { context: 1-call, heap: 24g, world-cache: ro }   # heap left out: ${VKR_HEAP:-24g}
    toolchain: { java-home: ${JAVA_HOME}, micans-jar: ${VKR_MICANS_JAR} }   # the same when left out
    gate:      { baseline: reports/taint-baseline.json, fail-on: new-chains }

${NAME} and ${NAME:-default} are replaced from the environment (defaults may nest). Relative paths
are relative to the directory of the configuration file. The tool reads built artifacts
(target/classes and the Spring Boot jar, or build/classes/java/main and build/libs); it never builds.

usage: vkr_config.py [--config vkr-taint.yml] [--require java,micans,artifacts]   (prints the result)
"""
import argparse
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vkr_yaml  # noqa: E402

CONFIG_NAME = 'vkr-taint.yml'
KNOWN_SINKS = ('cwe-89', 'cwe-117', 'cwe-79', 'cwe-918', 'cwe-1336', 'cwe-78', 'cwe-22')
DEFAULT_SINKS = ('cwe-89', 'cwe-117')  # the catalogues of the thesis measurements
SKIP_DIRS = {'target', 'build', 'node_modules', 'out', 'bin', '.git', '.idea', '.mvn', '.gradle', 'src'}
INFRA_MARKERS = ('@EnableConfigServer', '@EnableEurekaServer')
GATEWAY_DEP = 'spring-cloud-starter-gateway'
# a servlet application (a war deployed to a servlet container): its HttpServlet subclasses are the entries
SERVLET_MARKERS = ('extends HttpServlet', 'extends javax.servlet.http.HttpServlet',
                   'extends jakarta.servlet.http.HttpServlet')
# the library jars inside the deployable archive: a Spring Boot jar or a war
LIB_PREFIXES = ('BOOT-INF/lib/', 'WEB-INF/lib/')


class ConfigError(Exception):
    """A configuration problem; the message names exactly what is missing or wrong."""


# ------------------------------------------------------------------ ${ENV} substitution

def substitute(text, env, missing):
    """Replaces ${NAME} and ${NAME:-default}; unset names without a default are added to missing."""
    out, i = [], 0
    while i < len(text):
        j = text.find('${', i)
        if j < 0:
            out.append(text[i:])
            break
        out.append(text[i:j])
        depth, k = 1, j + 2
        while k < len(text) and depth:
            if text.startswith('${', k):
                depth += 1
                k += 2
                continue
            if text[k] == '}':
                depth -= 1
            k += 1
        if depth:
            raise ConfigError(f'unterminated ${{...}} in "{text}"')
        body = text[j + 2:k - 1]
        name, sep, default = body.partition(':-')
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name):
            raise ConfigError(f'bad variable name "{name}" in "{text}"')
        value = env.get(name)
        if value in (None, '') and sep:
            inner = []
            value = substitute(default, env, inner)
            if inner:  # neither the variable nor its default is set: name both
                missing.extend([name] + inner)
        if value is None:
            missing.append(name)
            value = ''
        out.append(value)
        i = k
    return ''.join(out)


def _vars(names):
    names = list(dict.fromkeys(names))
    if len(names) == 1:
        return f'environment variable {names[0]} is not set'
    return f'none of the environment variables {", ".join(names)} is set'


def _subst_tree(node, env, missing_by_path, path=''):
    if isinstance(node, dict):
        return {k: _subst_tree(v, env, missing_by_path, f'{path}.{k}' if path else k) for k, v in node.items()}
    if isinstance(node, list):
        return [_subst_tree(v, env, missing_by_path, f'{path}[{i}]') for i, v in enumerate(node)]
    if isinstance(node, str) and '${' in node:
        missing = []
        value = substitute(node, env, missing)
        if missing:
            missing_by_path[path] = (node, missing)
            return None if value == '' else value
        return value
    return node


# ------------------------------------------------------------------ module discovery

def _read(path):
    try:
        with open(path, encoding='utf-8', errors='replace') as f:
            return f.read()
    except OSError:
        return ''


def _build_file(d):
    for f in ('pom.xml', 'build.gradle', 'build.gradle.kts'):
        if os.path.isfile(os.path.join(d, f)):
            return os.path.join(d, f)
    return None


def _artifact_id(module_dir):
    pom = os.path.join(module_dir, 'pom.xml')
    if os.path.isfile(pom):
        text = re.sub(r'<parent>.*?</parent>', '', _read(pom), flags=re.S)
        text = re.sub(r'<dependencies>.*', '', text, flags=re.S)
        m = re.search(r'<artifactId>\s*([^<\s]+)\s*</artifactId>', text)
        if m:
            return m.group(1)
    return os.path.basename(module_dir)


def _java_sources(module_dir):
    src = os.path.join(module_dir, 'src', 'main', 'java')
    for d, dirs, files in os.walk(src):
        dirs.sort()
        for f in sorted(files):
            if f.endswith('.java'):
                yield os.path.join(d, f)


def find_modules(root):
    """Directories with a build file and Java sources (src/main/java), sorted by path."""
    found = []
    for d, dirs, _ in os.walk(root):
        dirs[:] = sorted(x for x in dirs if x not in SKIP_DIRS and not x.startswith('.'))
        if _build_file(d) and os.path.isdir(os.path.join(d, 'src', 'main', 'java')):
            found.append(d)
    return found


def classify_module(module_dir):
    """'service' (a @SpringBootApplication, or a war with HttpServlet subclasses), 'infra'
    (config/discovery server, gateway) or 'shared'."""
    texts = [_read(p) for p in _java_sources(module_dir)]
    boot = any('@SpringBootApplication' in t for t in texts)
    if not boot:
        war = re.search(r'<packaging>\s*war\s*</packaging>', _read(_build_file(module_dir) or ''))
        servlet = any(m in t for t in texts for m in SERVLET_MARKERS)
        return 'service' if war and servlet else 'shared'
    if any(m in t for t in texts for m in INFRA_MARKERS) or GATEWAY_DEP in _read(_build_file(module_dir) or ''):
        return 'infra'
    return 'service'


def classes_dir(module_dir):
    for c in (('target', 'classes'), ('build', 'classes', 'java', 'main')):
        p = os.path.join(module_dir, *c)
        if os.path.isdir(p):
            return p
    return None


def boot_jar(module_dir):
    """The deployable archive in target/ or build/libs/: a Spring Boot fat jar (has BOOT-INF/) or a war
    (has WEB-INF/)."""
    for d in (os.path.join(module_dir, 'target'), os.path.join(module_dir, 'build', 'libs')):
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            p = os.path.join(d, f)
            if f.endswith(('.jar', '.war')) and os.path.isfile(p):
                try:
                    with zipfile.ZipFile(p) as z:
                        if any(n.startswith(('BOOT-INF/', 'WEB-INF/')) for n in z.namelist()[:200]):
                            return p
                except zipfile.BadZipFile:
                    continue
    return None


# ------------------------------------------------------------------ loading

def _section(raw, key, errors, default=None):
    v = raw.get(key, default)
    if v is None:
        return {}
    if not isinstance(v, dict):
        errors.append(f'{key}: expected a mapping, got {v!r}')
        return {}
    return v


def _abs(base, p):
    return None if p in (None, '') else os.path.normpath(os.path.join(base, os.path.expanduser(str(p))))


def find_config(start=None):
    d = os.path.abspath(start or os.getcwd())
    while True:
        p = os.path.join(d, CONFIG_NAME)
        if os.path.isfile(p):
            return p
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


def load(path=None, env=None, require=()):
    """Returns the resolved configuration as a dict; raises ConfigError naming every problem.

    require: 'java' (toolchain.java-home with bin/javap), 'micans' (toolchain.micans-jar),
             'artifacts' (built classes and Spring Boot jar of every service, classes of shared modules)
    """
    env = os.environ if env is None else env
    if path is None:
        path = env.get('VKR_CONFIG') or find_config()
        if not path:
            raise ConfigError(f'no {CONFIG_NAME} found in the current directory or its parents; '
                              f'pass --config <file> or set VKR_CONFIG')
    if not os.path.isfile(path):
        raise ConfigError(f'configuration file not found: {path}')
    path = os.path.abspath(path)
    base = os.path.dirname(path)
    try:
        raw = vkr_yaml.load(path) or {}
    except vkr_yaml.YamlError as e:
        raise ConfigError(str(e)) from None
    if not isinstance(raw, dict):
        raise ConfigError(f'{path}: expected a mapping at the top level')
    errors = []
    unknown = set(raw) - {'version', 'project', 'services', 'shared', 'taint', 'analysis', 'toolchain', 'gate'}
    if unknown:
        errors.append(f'unknown top-level keys: {", ".join(sorted(unknown))}')
    if raw.get('version') != 1:
        errors.append(f'version: expected 1, got {raw.get("version")!r}')
    missing_env = {}
    raw = _subst_tree(raw, env, missing_env)
    toolchain_missing = {p: v for p, v in missing_env.items() if p.startswith('toolchain.')}
    for p, (text, names) in missing_env.items():
        if p not in toolchain_missing:
            errors.append(f'{p}: {_vars(names)} (used as "{text}")')

    project = _section(raw, 'project', errors)
    root = _abs(base, project.get('root', '.'))
    if not root or not os.path.isdir(root):
        errors.append(f'project.root: directory not found: {root}')
    gw = project.get('gateway-config', 'auto')
    gateway = None if gw in (None, 'auto') else _abs(base, gw)
    if gateway and not os.path.isfile(gateway):
        errors.append(f'project.gateway-config: file not found: {gateway}')

    cfg = {'path': path, 'base': base, 'root': root, 'gateway_config': gateway, 'services': [], 'shared': [],
           'notes': []}
    if root and os.path.isdir(root):
        modules = find_modules(root)
        kinds = {m: classify_module(m) for m in modules}
        by_name = {}
        for m in modules:
            by_name.setdefault(os.path.basename(m), m)
            by_name.setdefault(os.path.relpath(m, root).replace(os.sep, '/'), m)
        cfg['services'] = _modules(raw.get('services', 'auto'), 'services', kinds, by_name, root, base, errors, cfg)
        cfg['shared'] = _modules(raw.get('shared', 'auto'), 'shared', kinds, by_name, root, base, errors, cfg)
        if not cfg['services'] and not errors:
            errors.append(f'services: no @SpringBootApplication module found under {root}')

    taint = _section(raw, 'taint', errors)
    sinks = taint.get('sinks', list(DEFAULT_SINKS))
    if isinstance(sinks, str):
        sinks = [sinks]
    resolved_sinks = []
    for s in sinks or []:
        if s in KNOWN_SINKS:
            resolved_sinks.append(s)
        elif isinstance(s, str) and s.endswith(('.yml', '.yaml')):
            p = _abs(base, s)
            if not os.path.isfile(p):
                errors.append(f'taint.sinks: catalogue file not found: {p}')
            resolved_sinks.append(p)
        else:
            errors.append(f'taint.sinks: unknown catalogue "{s}" (built-in: {", ".join(KNOWN_SINKS)}, '
                          f'or a Tai-e .yml file with a sinks: section)')
    if taint.get('sources', 'auto') != 'auto':
        errors.append(f'taint.sources: only "auto" is supported, got {taint.get("sources")!r}')
    if taint.get('discovery', 'annotations') != 'annotations':
        errors.append(f'taint.discovery: only "annotations" is supported, got {taint.get("discovery")!r}')
    packages = taint.get('packages') or {}
    if not isinstance(packages, dict) or set(packages) - {'dto', 'entity', 'event'}:
        errors.append('taint.packages: expected a mapping with keys dto, entity, event')
        packages = {}
    overrides = _abs(base, taint.get('overrides'))
    if overrides and not os.path.isfile(overrides):
        errors.append(f'taint.overrides: file not found: {overrides}')
    base_config = taint.get('base-config') or None
    cfg['taint'] = {'sinks': resolved_sinks, 'sources': 'auto', 'discovery': 'annotations',
                    'packages': {k: list(packages.get(k) or []) for k in ('dto', 'entity', 'event')},
                    'overrides': overrides,
                    'base_config': _abs(base, base_config) if base_config else None}
    if overrides:
        try:
            cfg['overrides'] = load_overrides(overrides)
        except ConfigError as e:
            errors.append(str(e))
    else:
        cfg['overrides'] = {'common': {}, 'services': {}, 'exclusions': [], 'call_site_helpers': []}

    analysis = _section(raw, 'analysis', errors)
    ctx = str(analysis.get('context', '1-call'))
    if not re.fullmatch(r'ci|\d+-(call|obj|type)', ctx):
        errors.append(f'analysis.context: expected ci, k-call, k-obj or k-type, got {ctx!r}')
    heap = str(analysis.get('heap', env.get('VKR_HEAP') or '24g'))
    if not re.fullmatch(r'\d+[kKmMgG]?', heap):
        errors.append(f'analysis.heap: expected a JVM size such as 24g, got {heap!r}')
    wc = str(analysis.get('world-cache', 'off'))
    if wc not in ('off', 'rw', 'ro'):
        errors.append(f'analysis.world-cache: expected off, rw or ro, got {wc!r}')
    cfg['analysis'] = {'context': ctx, 'heap': heap, 'world_cache': wc}

    toolchain = dict(_section(raw, 'toolchain', errors))
    # a key left out takes the variable of the default configuration (the image sets both)
    for key, value in (('java-home', env.get('JAVA_HOME')),
                       ('micans-jar', env.get('VKR_MICANS_JAR') or env.get('MICANS_JAR'))):
        if key not in toolchain and value:
            toolchain[key] = value
    cfg['toolchain'] = {'java_home': _abs(base, toolchain.get('java-home')),
                        'micans_jar': _abs(base, toolchain.get('micans-jar'))}
    _check_toolchain(cfg, toolchain, toolchain_missing, require, errors)

    gate = _section(raw, 'gate', errors)
    fail_on = str(gate.get('fail-on', 'none'))
    if fail_on not in ('new-chains', 'none'):
        errors.append(f'gate.fail-on: expected new-chains or none, got {fail_on!r}')
    cfg['gate'] = {'baseline': _abs(base, gate.get('baseline')), 'fail_on': fail_on}

    if 'artifacts' in require and not errors:
        for m in cfg['services']:
            if not m['classes']:
                errors.append(f'services.{m["name"]}: no built classes in {m["module"]} '
                              f'(target/classes or build/classes/java/main); the tool does not build, run the build first')
            if not m['jar']:
                errors.append(f'services.{m["name"]}: no Spring Boot jar (with BOOT-INF/) or war in {m["module"]}/target '
                              f'or build/libs; the tool does not build, run the build first')
        for m in cfg['shared']:
            if not m['classes']:
                errors.append(f'shared.{m["name"]}: no built classes in {m["module"]}')
    if errors:
        raise ConfigError(f'{path}:\n  ' + '\n  '.join(errors))
    return cfg


def _check_toolchain(cfg, toolchain, missing, require, errors):
    for key, need, what in (('java-home', 'java', 'a JDK home (17 or newer)'),
                            ('micans-jar', 'micans', 'the Micans build jar (micans.jar)')):
        if need not in require:
            continue
        p = f'toolchain.{key}'
        if p in missing:
            text, names = missing[p]
            errors.append(f'{p}: {_vars(names)} (used as "{text}"); '
                          f'set it to {what} or write the path in the configuration')
            continue
        if not toolchain.get(key):
            errors.append(f'{p}: missing; set it to {what}')
            continue
        v = cfg['toolchain'][key.replace('-', '_')]
        if need == 'java':
            exe = '.exe' if os.name == 'nt' else ''
            if not os.path.isdir(v):
                errors.append(f'{p}: directory not found: {v}')
            elif not os.path.isfile(os.path.join(v, 'bin', 'javap' + exe)):
                errors.append(f'{p}: {v} has no bin/javap{exe} (a JDK is needed, not a JRE)')
        elif not os.path.isfile(v):
            errors.append(f'{p}: file not found: {v}')


def _module(name, module_dir, kind, jar=None, classes=None, artifact=None):
    m = {'name': name, 'module': module_dir, 'classes': classes or classes_dir(module_dir)}
    if kind == 'services':
        m['jar'] = jar or boot_jar(module_dir)
    else:
        m['artifact'] = artifact or _artifact_id(module_dir)
    return m


def _modules(spec, kind, kinds, by_name, root, base, errors, cfg):
    want = 'service' if kind == 'services' else 'shared'
    if spec in (None, 'auto'):
        out = [_module(os.path.basename(m), m, kind) for m, k in kinds.items() if k == want]
        if kind == 'services':
            skipped = [os.path.basename(m) for m, k in kinds.items() if k == 'infra']
            if skipped:
                cfg['notes'].append(f'infrastructure modules not analysed: {", ".join(skipped)}')
        return out
    if not isinstance(spec, list):
        errors.append(f'{kind}: expected "auto" or a list, got {spec!r}')
        return []
    out = []
    for item in spec:
        if isinstance(item, str):
            item = {'name': item}
        if not isinstance(item, dict) or not item.get('name'):
            errors.append(f'{kind}: every entry needs a name, got {item!r}')
            continue
        name = str(item['name'])
        if item.get('module'):
            d = _abs(base, item['module'])
        else:
            d = by_name.get(name)
        if not d or not os.path.isdir(d):
            errors.append(f'{kind}.{name}: module directory not found'
                          + (f' ({d})' if d else f' (no module named {name} under {root}; set module:)'))
            continue
        out.append(_module(name, d, kind, jar=_abs(base, item.get('jar')), classes=_abs(base, item.get('classes')),
                           artifact=item.get('artifact')))
    return out


# ------------------------------------------------------------------ overrides

OVERRIDE_SECTIONS = ('sources', 'sinks', 'transfers', 'sanitizers')


def load_overrides(path):
    """Tai-e taint-config YAML, merged last into every generated config, with two extensions:
      services: {<name>: {sources|sinks|transfers|sanitizers: [...]}}   entries for one service only
      exclusions: [{field|method: <signature>, reason: <why>}]            drops generated entries
      call-site-helpers: [{method: <signature>, reason: <why>}]           static String helpers
    Every exclusion and helper must carry a non-empty reason."""
    try:
        raw = vkr_yaml.load(path) or {}
    except vkr_yaml.YamlError as e:
        raise ConfigError(str(e)) from None
    errs = []
    unknown = set(raw) - set(OVERRIDE_SECTIONS) - {'services', 'exclusions', 'call-site-helpers', 'call-site-mode'}
    if unknown:
        errs.append(f'unknown keys {", ".join(sorted(unknown))}')

    def entries(node, where):
        out = {}
        for sec in OVERRIDE_SECTIONS:
            v = node.get(sec) or []
            if not isinstance(v, list) or not all(isinstance(e, dict) for e in v):
                errs.append(f'{where}{sec}: expected a list of mappings')
                continue
            out[sec] = v
        return out

    common = entries(raw, '')
    services = {}
    for name, node in (raw.get('services') or {}).items():
        if not isinstance(node, dict):
            errs.append(f'services.{name}: expected a mapping')
            continue
        services[name] = entries(node, f'services.{name}.')
    reasoned = {}
    for key in ('exclusions', 'call-site-helpers'):
        items = raw.get(key) or []
        for i, e in enumerate(items):
            target = isinstance(e, dict) and (e.get('field') or e.get('method'))
            if not target:
                errs.append(f'{key}[{i}]: needs field: or method: (got {e!r})')
            elif not str(e.get('reason') or '').strip():
                errs.append(f'{key}[{i}] ({target}): every entry must carry a reason')
        reasoned[key] = items
    if errs:
        raise ConfigError(f'{path}: ' + '; '.join(errs))
    return {'common': common, 'services': services, 'exclusions': reasoned['exclusions'],
            'call_site_helpers': reasoned['call-site-helpers']}


def short_name(service):
    return service[:-len('-service')] if service.endswith('-service') else service


def base_config_path(cfg, service):
    t = cfg['taint']['base_config']
    return t.replace('{service}', service).replace('{short}', short_name(service)) if t else None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--config')
    ap.add_argument('--require', default='', help='comma list of java, micans, artifacts')
    a = ap.parse_args()
    try:
        cfg = load(a.config, require=tuple(x for x in a.require.split(',') if x))
    except ConfigError as e:
        sys.exit(f'vkr-taint: {e}')
    cfg = dict(cfg)
    cfg.pop('overrides', None)
    print(json.dumps(cfg, indent=1))


if __name__ == '__main__':
    main()
