"""Generates the base taint config of a service (taint-<service>.yml, Tai-e format) from the channel
model and the service bytecode, replacing the hand-written config/taint-<x>.yml.

  sources   = parameters of the service's externally reachable endpoints (exposed through the gateway;
              every endpoint when the model has no gateway) that a client controls: not primitives,
              enums or framework-built values (Pageable, Principal, ...); plus the payload parameter of
              every AMQP listener (marked "# AMQP listener": mk_field_config.py replaces it by the
              event field sources)
  sinks     = outgoing channel ends from the model (OpenFeign client methods, AmqpTemplate sends when
              spring-amqp is packaged, the outbox enqueue as channel-extractor identifies publishers)
              + the selected sink catalogues (cwe-89: JPA, JdbcTemplate, MyBatis ${} mappers;
              cwe-117: slf4j)
  transfers = accessors of the discovered request DTO packages and event packages, event constructors
              (services that publish), and a JDK catalogue (boxing, collections, streams, strings)

Field-level parts (DTO/event/entity field sources, database channel) are added afterwards by the
mk_*.py scripts from the discovery written next to the config (vkr_prepare.py); the overrides file is
merged last. Signatures are read with javap (JDK of toolchain.java-home) from the built classes.

usage: gen_taint_config.py [--config vkr-taint.yml] --model channels.json [--services a,b]
                           --out-dir DIR          -> DIR/taint-<service>.yml, DIR/discovery-<service>.json
"""
import argparse
import json
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vkr_config  # noqa: E402
from vkr_yaml import dump_entry  # noqa: E402

PRIMITIVES = {'boolean', 'byte', 'char', 'short', 'int', 'long', 'float', 'double', 'void'}
# built by the framework from the request, not text a client chooses
FRAMEWORK_TYPES = {
    'org.springframework.data.domain.Pageable', 'org.springframework.data.domain.Sort',
    'java.security.Principal', 'org.springframework.security.core.Authentication',
    'jakarta.servlet.http.HttpServletRequest', 'jakarta.servlet.http.HttpServletResponse',
    'javax.servlet.http.HttpServletRequest', 'javax.servlet.http.HttpServletResponse',
    'org.springframework.web.server.ServerWebExchange', 'org.springframework.validation.BindingResult',
    'java.util.Locale',
}
# the outbox signature channel-extractor uses by default (AmqpExtractor.DEFAULT_OUTBOX_SIGNATURES):
# a call <receiver whose type ends with OutboxService>.enqueue(exchange, routingKey, payload, ...)
OUTBOX_SIGNATURES = [('OutboxService', 'enqueue', 2)]
ENTITY_ANNOTATIONS = (b'Ljakarta/persistence/Entity;', b'Ljavax/persistence/Entity;')

CATALOGUES = {
    'cwe-89': [
        ('SQL injection', [
            {'method': '<jakarta.persistence.EntityManager: jakarta.persistence.Query createNativeQuery(java.lang.String)>', 'index': 0},
            {'method': '<jakarta.persistence.EntityManager: jakarta.persistence.Query createQuery(java.lang.String)>', 'index': 0},
            {'method': '<org.springframework.jdbc.core.JdbcTemplate: * query*(java.lang.String,*{0+})>', 'index': 0},
            {'method': '<org.springframework.jdbc.core.JdbcTemplate: int update(java.lang.String,*{0+})>', 'index': 0},
            {'method': '<org.springframework.jdbc.core.JdbcTemplate: void execute(java.lang.String)>', 'index': 0},
        ]),
    ],
    'cwe-117': [
        ('log injection (CWE-117)', [
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object)>', 'index': 1},
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object,java.lang.Object)>', 'index': 1},
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object,java.lang.Object)>', 'index': 2},
        ]),
        ('varargs: wildcard method names cannot be combined with array types in Tai-e patterns', [
            {'method': f'<org.slf4j.Logger: void {m}(java.lang.String,java.lang.Object[])>', 'index': '1[*]'}
            for m in ('info', 'warn', 'error', 'debug')
        ]),
    ],
}

JDK_TRANSFERS = [
    ('boxing', [
        ('<java.lang.Long: java.lang.Long valueOf(long)>', 0, 'result'),
        ('<java.lang.Long: long longValue()>', 'base', 'result'),
    ]),
    ('collections built from a tainted collection/element (factory methods only)', [
        ('<java.util.List: java.util.List of(java.lang.Object)>', 0, 'result'),
        ('<java.util.List: java.util.List copyOf(java.util.Collection)>', 0, 'result'),
        ('<java.util.Set: java.util.Set copyOf(java.util.Collection)>', 0, 'result'),
        ('<java.util.Collection^: java.util.stream.Stream stream()>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.util.stream.Stream map(java.util.function.Function)>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.util.stream.Stream filter(java.util.function.Predicate)>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.util.List toList()>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.lang.Object collect(java.util.stream.Collector)>', 'base', 'result'),
    ]),
    ('strings', [
        ('<java.lang.String: java.lang.String valueOf(java.lang.Object)>', 0, 'result'),
        ('<java.lang.String: java.lang.String format(java.lang.String,java.lang.Object[])>', '1[*]', 'result'),
        ('<java.lang.String: java.lang.String substring(int,int)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String trim()>', 'base', 'result'),
        ('<java.lang.String: java.lang.String strip()>', 'base', 'result'),
        ('<java.lang.String: java.lang.String toUpperCase(java.util.Locale)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String toLowerCase(java.util.Locale)>', 'base', 'result'),
        ('<java.lang.AbstractStringBuilder^: * append(java.lang.Object^)>', 0, 'base'),
        ('<java.lang.AbstractStringBuilder^: * append(java.lang.String)>', 0, 'base'),
        ('<java.lang.AbstractStringBuilder^: * toString()>', 'base', 'result'),
    ]),
    ('maps keyed by request values', [
        ('<java.util.Map^: java.lang.Object put(java.lang.Object,java.lang.Object)>', 0, 'base'),
        ('<java.util.Map^: java.lang.Object merge(java.lang.Object,java.lang.Object,java.util.function.BiFunction)>', 0, 'base'),
        ('<java.util.Map^: java.util.Set keySet()>', 'base', 'result'),
    ]),
    ('iterating a tainted request list', [
        ('<java.lang.Iterable^: java.util.Iterator iterator()>', 'base', 'result'),
        ('<java.util.Iterator^: java.lang.Object next()>', 'base', 'result'),
        ('<java.util.List^: java.lang.Object get(int)>', 'base', 'result'),
    ]),
    ('i18n messages embed their arguments; no ^: Tai-e cannot combine a subtype pattern with an array '
     'parameter type', [
        ('<org.springframework.context.MessageSource: java.lang.String getMessage(java.lang.String,java.lang.Object[],java.util.Locale)>', '1[*]', 'result'),
        ('<java.util.Map$Entry^: java.lang.Object getKey()>', 'base', 'result'),
        ('<java.util.Map$Entry^: java.lang.Object getValue()>', 'base', 'result'),
    ]),
]


class GenError(Exception):
    pass


# ------------------------------------------------------------------ bytecode (javap)

_DESC = re.compile(r'\[*(?:[BCDFIJSZV]|L[^;]+;)')
_PRIM = {'B': 'byte', 'C': 'char', 'D': 'double', 'F': 'float', 'I': 'int', 'J': 'long', 'S': 'short',
         'Z': 'boolean', 'V': 'void'}


def desc_type(d):
    dims = len(d) - len(d.lstrip('['))
    d = d[dims:]
    t = _PRIM[d] if len(d) == 1 else d[1:-1].replace('/', '.')
    return t + '[]' * dims


def parse_descriptor(desc):
    m = re.fullmatch(r'\((.*)\)(.+)', desc)
    return [desc_type(x) for x in _DESC.findall(m.group(1))], desc_type(m.group(2))


class ClassInfo:
    def __init__(self, name, header):
        self.name = name
        self.header = header
        self.is_enum = ' extends java.lang.Enum<' in header
        self.is_record = ' extends java.lang.Record' in header
        self.is_interface = re.search(r'\binterface\b', header.split(name)[0]) is not None
        self.methods = []  # (name, [param types], return type, static)
        self.fields = []   # (type, name, modifiers)

    def find(self, name, argc=None):
        return [m for m in self.methods if m[0] == name and (argc is None or len(m[1]) == argc)]


def tai_sig(cls, m):
    return f'<{cls}: {m[2]} {m[0]}({",".join(m[1])})>'


def javap(java_home, classpath, classes, chunk=150):
    """{class name: ClassInfo} for the classes javap finds; one JVM per chunk of classes."""
    exe = os.path.join(java_home, 'bin', 'javap')
    out = {}
    classes = sorted(set(classes))
    for i in range(0, len(classes), chunk):
        r = subprocess.run([exe, '-p', '-s', '-cp', os.pathsep.join(classpath), *classes[i:i + chunk]],
                           capture_output=True, text=True, encoding='utf-8', errors='replace')
        out.update(parse_javap(r.stdout))
    return out


def parse_javap(text):
    result, cur, pending = {}, None, None
    for line in text.splitlines():
        if not line.startswith(' '):
            if line.startswith('Compiled from') or not line.strip():
                continue
            if line.startswith('}'):
                cur = None
                continue
            m = re.search(r'\b(?:class|interface|enum|record)\s+([\w.$]+)', line)
            if m and line.rstrip().endswith('{'):
                cur = ClassInfo(m.group(1), line)
                result[cur.name] = cur
            continue
        if cur is None:
            continue
        s = line.strip()
        if s.startswith('descriptor:') and pending is not None:
            desc = s.split(':', 1)[1].strip()
            decl = pending
            pending = None
            if '(' in desc:
                params, ret = parse_descriptor(desc)
                head = decl.split('(', 1)[0].split()
                name = head[-1] if head else ''
                if name == cur.name:
                    name, ret = '<init>', 'void'
                if name and not decl.startswith('static {}'):
                    cur.methods.append((name, params, ret, 'static' in head[:-1]))
            else:
                parts = decl.rstrip(';').split()
                cur.fields.append((desc_type(desc), parts[-1], parts[:-2]))
            continue
        if line.startswith('  ') and not line.startswith('    '):
            pending = s
    return result


def class_files(root):
    """Class names under a classes directory (nested classes included)."""
    out = []
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith('.class') and f != 'module-info.class' and f != 'package-info.class':
                rel = os.path.relpath(os.path.join(d, f), root)[:-6]
                out.append(rel.replace(os.sep, '.'))
    return sorted(out)


def common_package(names):
    pkgs = [n.rsplit('.', 1)[0].split('.') for n in names if '.' in n]
    if not pkgs:
        return ''
    first = pkgs[0]
    k = len(first)
    for p in pkgs[1:]:
        k = min(k, next((i for i, (a, b) in enumerate(zip(first, p)) if a != b), min(len(first), len(p))))
    return '.'.join(first[:k])


def pkg(cls):
    return cls.rsplit('.', 1)[0]


def simple(cls):
    return cls.rsplit('.', 1)[-1].split('$')[-1]


# ------------------------------------------------------------------ generation

class Service:
    """Bytecode view of one service: its classes plus the shared modules' classes."""

    def __init__(self, cfg, module, java_home):
        self.name = module['name']
        self.java_home = java_home
        self.app = module['classes']
        self.jar = module.get('jar')
        self.shared = [m['classes'] for m in cfg['shared'] if m.get('classes')]
        self.classpath = [self.app, *self.shared]
        self.app_classes = class_files(self.app)
        self.shared_classes = [c for d in self.shared for c in class_files(d)]
        self.by_simple = {}
        for c in self.app_classes + self.shared_classes:
            self.by_simple.setdefault(simple(c), []).append(c)
        self.info = {}

    def load(self, classes):
        need = [c for c in classes if c and c not in self.info]
        if need:
            got = javap(self.java_home, self.classpath, need)
            self.info.update(got)
            for c in need:
                self.info.setdefault(c, None)

    def cls(self, name):
        self.load([name])
        return self.info.get(name)

    def resolve_simple(self, name):
        """FQNs of an application/shared class by simple name; app classes first."""
        hits = self.by_simple.get(name, [])
        return sorted(hits, key=lambda c: (c not in self.app_classes, c))

    def has_library(self, prefix):
        if not self.jar:
            return False
        with zipfile.ZipFile(self.jar) as z:
            return any(n.startswith('BOOT-INF/lib/' + prefix) for n in z.namelist())

    def entity_classes(self):
        out = []
        for c in self.app_classes:
            p = os.path.join(self.app, *c.split('.')) + '.class'
            with open(p, 'rb') as f:
                data = f.read()
            if any(a in data for a in ENTITY_ANNOTATIONS):
                out.append(c)
        return out


def _type_matches(model_type, bytecode_type):
    """channel-extractor writes source-level simple types (List, Long, CouponRequest)."""
    mt = re.sub(r'<.*', '', model_type or '').strip()
    mt = mt.replace('...', '[]')
    return simple(bytecode_type.replace('[]', '')) == simple(mt.replace('[]', '')) or not mt


def _pick_method(info, name, params):
    cands = info.find(name, len(params)) if info else []
    if len(cands) > 1:
        cands = [m for m in cands if all(_type_matches(p.get('type'), t) for p, t in zip(params, m[1]))] or cands
    return cands[0] if cands else None


def generate(cfg, model, module, java_home):
    """(config text, discovery dict, notes) for one service."""
    svc = Service(cfg, module, java_home)
    name = svc.name
    notes = []
    gateway = any(e.get('exposedViaGateway') for e in model.get('endpoints', []))
    endpoints = [e for e in model.get('endpoints', []) if e['service'] == name
                 and (e.get('exposedViaGateway') or not gateway)]
    if not gateway:
        notes.append('no endpoint of the model is exposed through a gateway: every endpoint is a source')
    listeners = [l for l in model.get('listeners', []) if l['service'] == name]
    clients = [c for c in model.get('httpClients', []) if c['service'] == name]
    publishers = [p for p in model.get('publishers', []) if p['service'] == name]
    svc.load([e['handler']['className'] for e in endpoints] + [l['handler']['className'] for l in listeners]
             + [c['declaration']['className'] for c in clients]
             + [p['sendSite']['className'] for p in publishers])

    def is_enum(t):
        if '.' not in t or t.startswith('java.'):
            return False
        i = svc.cls(t)
        return bool(i and i.is_enum)

    # ---- sources
    sources, dto_classes = [], set()
    for e in sorted(endpoints, key=lambda e: (e['handler']['className'], e['handler']['line'])):
        h = e['handler']
        m = _pick_method(svc.info.get(h['className']), h['method'], e['params'])
        if not m:
            notes.append(f'endpoint {e["id"]}: {h["className"]}#{h["method"]} with {len(e["params"])} parameters '
                         f'not found in the bytecode')
            continue
        for i, (p, t) in enumerate(zip(e['params'], m[1])):
            if p.get('source') == 'FRAMEWORK' or t in PRIMITIVES or t in FRAMEWORK_TYPES or is_enum(t):
                continue
            sources.append({'kind': 'param', 'method': tai_sig(h['className'], m), 'index': i})
            if (t in svc.app_classes or t in svc.shared_classes) and not t.endswith('[]'):
                dto_classes.add(t)
    listener_sources = []
    payload_types = set()
    for l in sorted(listeners, key=lambda l: (l['handler']['className'], l['handler']['line'])):
        h = l['handler']
        info = svc.info.get(h['className'])
        cands = info.find(h['method']) if info else []
        hit = None
        for m in cands:
            for i, t in enumerate(m[1]):
                if simple(t) == (l.get('payloadType') or ''):
                    hit = (m, i)
                    break
            if hit:
                break
        if not hit and len(cands) == 1 and len(cands[0][1]) == 1:
            hit = (cands[0], 0)
        if not hit:
            notes.append(f'listener {l["id"]}: payload parameter of {h["className"]}#{h["method"]} not found')
            continue
        entry = {'kind': 'param', 'method': tai_sig(h['className'], hit[0]), 'index': hit[1]}
        if entry not in listener_sources:
            listener_sources.append(entry)
        payload_types.add(l.get('payloadType'))
    payload_types |= {p.get('payloadType') for p in publishers}

    # ---- discovery: DTO, event, entity classes and packages
    pk = cfg['taint']['packages']
    dto_packages = sorted(pk['dto'] or {pkg(c) for c in dto_classes})
    event_classes = sorted({c for t in payload_types if t for c in svc.resolve_simple(t)[:1]})
    event_packages = sorted(pk['event'] or {pkg(c) for c in event_classes})
    entity_classes = svc.entity_classes()
    entity_packages = sorted(pk['entity'] or {pkg(c) for c in entity_classes})
    in_event_pkgs = [c for c in svc.app_classes + svc.shared_classes if pkg(c) in event_packages and '$' not in c]
    svc.load(in_event_pkgs)
    max_ctor = max([len(m[1]) for c in in_event_pkgs if svc.info.get(c)
                    for m in svc.info[c].find('<init>')] or [0])

    # ---- sinks
    sink_groups = []
    feign = []
    for c in clients:
        d = c['declaration']
        m = _pick_method(svc.info.get(d['className']), d['method'], c.get('params') or [])
        if not m:
            notes.append(f'http client {c["id"]}: {d["className"]}#{d["method"]} not found in the bytecode')
            continue
        for i, t in enumerate(m[1]):
            p = (c.get('params') or [{}] * len(m[1]))[i]
            if t not in PRIMITIVES and p.get('source') != 'FRAMEWORK':
                e = {'method': tai_sig(d['className'], m), 'index': i}
                if e not in feign:
                    feign.append(e)
    if feign:
        sink_groups.append(('outgoing HTTP channel (OpenFeign)', feign))
    amqp = []
    outbox_sinks = []
    outbox_pubs = [p for p in publishers if p.get('mechanism') == 'OUTBOX']
    if outbox_pubs:
        svc_cands = [c for suffix, _, _ in OUTBOX_SIGNATURES for c in svc.app_classes + svc.shared_classes
                     if simple(c).endswith(suffix) and '$' not in c]
        svc.load(svc_cands)
        enqueue = []
        for suffix, meth, idx in OUTBOX_SIGNATURES:
            for c in svc_cands:
                i = svc.info.get(c)
                for m in (i.find(meth) if i else []):
                    if len(m[1]) > idx:
                        enqueue.append((c, m, idx))
        payload_param_types = {m[1][idx] for _, m, idx in enqueue}
        # Publisher whose origin differs from its send site: channel-extractor resolved the routing
        # key through the callers of a helper. When the helper receives the payload as a parameter,
        # the sink is put on that parameter, so that each call site maps to one publisher id;
        # a helper that builds the payload itself keeps the sink at the enqueue call.
        needs_enqueue = False
        for p in outbox_pubs:
            s = p['sendSite']
            hit = None
            if (s['className'], s['method']) != (p['origin']['className'], p['origin']['method']):
                info = svc.info.get(s['className'])
                payload_fqn = set(svc.resolve_simple(p.get('payloadType') or '')[:1])
                for m in (info.find(s['method']) if info else []):
                    idx = next((i for i, t in enumerate(m[1]) if t in payload_param_types | payload_fqn), None)
                    if idx is not None:
                        hit = {'method': tai_sig(s['className'], m), 'index': idx}
            if hit is None:
                needs_enqueue = True
            elif hit not in amqp:
                amqp.append(hit)
                outbox_sinks.append([s['className'], s['method']])
        if needs_enqueue:
            if not enqueue:
                notes.append('outbox publishers found, but no <...OutboxService>.enqueue method in the bytecode')
            for c, m, idx in enqueue:
                amqp.append({'method': tai_sig(c, m), 'index': idx})
                outbox_sinks.append([c, m[0]])
    if svc.has_library('spring-amqp-') or svc.has_library('spring-rabbit-'):
        amqp += [{'method': '<org.springframework.amqp.core.AmqpTemplate^: void convertAndSend(java.lang.String,java.lang.String,java.lang.Object)>', 'index': 2},
                 {'method': '<org.springframework.amqp.core.AmqpTemplate^: void convertAndSend(java.lang.String,java.lang.Object)>', 'index': 1}]
    if amqp:
        sink_groups.append(('outgoing AMQP channel' + (' (transactional outbox: enqueue = send)' if outbox_pubs else ''),
                            amqp))
    for cat in cfg['taint']['sinks']:
        if cat in CATALOGUES:
            sink_groups += CATALOGUES[cat]
            if cat == 'cwe-89':
                mb = mybatis_sinks(svc, notes)
                if mb:
                    sink_groups.append(('SQL injection: MyBatis ${} substitution', mb))
        else:
            from vkr_yaml import load
            sink_groups.append((f'catalogue {os.path.basename(cat)}', (load(cat) or {}).get('sinks') or []))

    # ---- transfers
    transfer_groups = []
    acc = [{'method': f'<{p}.*: * *()>', 'from': 'base', 'to': 'result'} for p in dto_packages + event_packages]
    if acc:
        transfer_groups.append(('DTO / event record accessors carry taint from the record to its components', acc))
    if publishers and event_packages:
        transfer_groups.append((
            'an event built from a tainted component is tainted (the payload crosses the channel as a whole)',
            [{'method': f'<{p}.*: void <init>(*{{{n}+}})>', 'from': n - 1, 'to': 'base'}
             for p in event_packages for n in range(1, max_ctor + 1)]))
    for title, items in JDK_TRANSFERS:
        transfer_groups.append((title, [{'method': m, 'from': f, 'to': t} for m, f, t in items]))

    lines = [f'# Generated by gen_taint_config.py for {name} - do not edit; add entries to the overrides file.',
             f'# Sources = externally reachable endpoints + AMQP listeners of the channel model; sinks = sink',
             f'# catalogues ({", ".join(os.path.basename(s) for s in cfg["taint"]["sinks"])}) + outgoing channel ends.',
             '', 'call-site-mode: true', '', 'sources:']
    if sources:
        lines.append('  # HTTP endpoints')
        lines += [f'  - {dump_entry(e)}' for e in sources]
    if listener_sources:
        lines.append('  # AMQP listener payloads (replaced by the event field sources in the field-level config)')
        lines += [f'  - {dump_entry(e)}  # AMQP listener' for e in listener_sources]
    if dto_packages:
        lines.append('  # request-DTO field sources are generated (scripts/mk_dto_fields.py)')
    lines += ['', 'sinks:']
    for title, items in sink_groups:
        lines.append(f'  # {title}')
        lines += [f'  - {dump_entry(e)}' for e in items]
    lines += ['', 'transfers:']
    for title, items in transfer_groups:
        lines.append(f'  # {title}')
        lines += [f'  - {dump_entry(e)}' for e in items]
    app_packages = sorted({p for p in [common_package(svc.app_classes)]
                           + [common_package(class_files(d)) for d in svc.shared] if p})
    discovery = {'service': name, 'appPackages': app_packages, 'dtoPackages': dto_packages,
                 'dtoClasses': sorted(dto_classes), 'eventPackages': event_packages, 'eventClasses': event_classes,
                 'entityPackages': entity_packages, 'entityClasses': entity_classes,
                 'outboxSinks': outbox_sinks, 'notes': notes}
    return '\n'.join(lines) + '\n', discovery, notes


def mybatis_sinks(svc, notes):
    """XML mappers: a statement whose text has ${...} splices a parameter into the SQL, so the mapper
    method (namespace = interface, id = method) is a sink at its parameter(s). With one parameter the
    index is 0; with several, every non-primitive one (@Param names are not matched: no -v parse)."""
    out = []
    for d, _, files in os.walk(svc.app):
        for f in files:
            if not f.endswith('.xml'):
                continue
            with open(os.path.join(d, f), encoding='utf-8', errors='replace') as fh:
                text = fh.read()
            ns = re.search(r'<mapper\s+namespace="([\w.$]+)"', text)
            if not ns:
                continue
            for stmt in re.finditer(r'<(select|insert|update|delete)\b[^>]*\bid="(\w+)"[^>]*>(.*?)</\1>', text, re.S):
                if '${' not in stmt.group(3):
                    continue
                info = svc.cls(ns.group(1))
                ms = info.find(stmt.group(2)) if info else []
                if not ms:
                    notes.append(f'MyBatis {ns.group(1)}.{stmt.group(2)}: mapper method not found')
                for m in ms:
                    idxs = [0] if len(m[1]) == 1 else [i for i, t in enumerate(m[1]) if t not in PRIMITIVES]
                    for i in idxs:
                        e = {'method': tai_sig(ns.group(1), m), 'index': i}
                        if e not in out:
                            out.append(e)
    return out


def generate_all(cfg, model, out_dir, services=None):
    java_home = cfg['toolchain']['java_home']
    os.makedirs(out_dir, exist_ok=True)
    results = {}
    for m in cfg['services']:
        if services and m['name'] not in services:
            continue
        text, disc, notes = generate(cfg, model, m, java_home)
        with open(os.path.join(out_dir, f'taint-{m["name"]}.yml'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
        with open(os.path.join(out_dir, f'discovery-{m["name"]}.json'), 'w', encoding='utf-8') as f:
            json.dump(disc, f, indent=1)
        results[m['name']] = (text, disc)
        for n in notes:
            print(f'{m["name"]}: note: {n}', file=sys.stderr)
    return results


def main():
    ap = argparse.ArgumentParser(description='generates taint-<service>.yml from the channel model')
    ap.add_argument('--config')
    ap.add_argument('--model', required=True)
    ap.add_argument('--services')
    ap.add_argument('--out-dir', required=True)
    a = ap.parse_args()
    try:
        cfg = vkr_config.load(a.config, require=('java', 'artifacts'))
    except vkr_config.ConfigError as e:
        sys.exit(f'vkr-taint: {e}')
    model = json.load(open(a.model, encoding='utf-8'))
    res = generate_all(cfg, model, a.out_dir, a.services.split(',') if a.services else None)
    for name, (text, _) in res.items():
        n = {s: 0 for s in ('sources', 'sinks', 'transfers')}
        sec = None
        for line in text.splitlines():
            if line.rstrip(':') in n and not line.startswith(' '):
                sec = line.rstrip(':')
            elif line.startswith('  - ') and sec:
                n[sec] += 1
        print(f'{name}: {n["sources"]} sources, {n["sinks"]} sinks, {n["transfers"]} transfers -> '
              f'{os.path.join(a.out_dir, "taint-" + name + ".yml")}')


if __name__ == '__main__':
    main()
