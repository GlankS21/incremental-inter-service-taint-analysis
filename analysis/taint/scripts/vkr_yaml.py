"""A small YAML subset reader/writer, so that the tool runs on a bare Python (no PyYAML).

Supported: block mappings and sequences by indentation, flow mappings/sequences ({a: 1}, [a, b]),
single/double-quoted and plain scalars, ints, true/false/null, and # comments. This covers
vkr-taint.yml and Tai-e taint configs as written in this repository (one flow mapping per entry).
Block scalars (> and |) are read as plain lines without comments. Not supported: anchors, tags,
quoted multi-line scalars, multiple documents.
"""
import json
import re


class YamlError(ValueError):
    pass


def _strip_comment(line):
    out, quote, i = [], None, 0
    while i < len(line):
        c = line[i]
        if quote:
            out.append(c)
            if c == '\\' and quote == '"' and i + 1 < len(line):
                out.append(line[i + 1])
                i += 1
            elif c == quote:
                quote = None
        elif c in '"\'' and (not out or out[-1] in ' \t[{,:-'):
            quote = c
            out.append(c)
        elif c == '#' and (not out or out[-1] in ' \t'):
            break
        else:
            out.append(c)
        i += 1
    return ''.join(out).rstrip()


def _scalar(text):
    t = text.strip()
    if t == '' or t in ('~', 'null', 'Null', 'NULL'):
        return None
    if t[0] == '"':
        if len(t) < 2 or t[-1] != '"':
            raise YamlError(f'unterminated string: {t}')
        return json.loads(t)
    if t[0] == "'":
        if len(t) < 2 or t[-1] != "'":
            raise YamlError(f'unterminated string: {t}')
        return t[1:-1].replace("''", "'")
    if t in ('true', 'True', 'TRUE'):
        return True
    if t in ('false', 'False', 'FALSE'):
        return False
    if re.fullmatch(r'[-+]?\d+', t):
        return int(t)
    return t


class _Flow:
    """Parser for one flow value: {..}, [..] or a scalar."""

    def __init__(self, text):
        self.s, self.i = text, 0

    def ws(self):
        while self.i < len(self.s) and self.s[self.i] in ' \t':
            self.i += 1

    def value(self, stops):
        self.ws()
        if self.i >= len(self.s):
            return None
        c = self.s[self.i]
        if c == '{':
            return self.mapping()
        if c == '[':
            return self.sequence()
        if c in '"\'':
            j = self.i + 1
            while j < len(self.s):
                if self.s[j] == '\\' and c == '"':
                    j += 2
                    continue
                if self.s[j] == c:
                    if c == "'" and j + 1 < len(self.s) and self.s[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            tok = self.s[self.i:j + 1]
            self.i = j + 1
            return _scalar(tok)
        j = self.i
        depth = 0
        while j < len(self.s):
            ch = self.s[j]
            if ch in '<(':
                depth += 1
            elif ch in '>)':
                depth -= 1
            elif depth <= 0 and ch in stops:
                if ch != ':' or j + 1 >= len(self.s) or self.s[j + 1] in ' \t':
                    break
            j += 1
        tok = self.s[self.i:j]
        self.i = j
        return _scalar(tok)

    def expect(self, ch):
        self.ws()
        if self.i >= len(self.s) or self.s[self.i] != ch:
            raise YamlError(f'expected {ch!r} at column {self.i + 1} in: {self.s}')
        self.i += 1

    def mapping(self):
        self.expect('{')
        out = {}
        self.ws()
        if self.s[self.i:self.i + 1] == '}':
            self.i += 1
            return out
        while True:
            key = self.value(':,}')
            self.expect(':')
            out[str(key)] = self.value(',}')
            self.ws()
            if self.s[self.i:self.i + 1] == ',':
                self.i += 1
                continue
            self.expect('}')
            return out

    def sequence(self):
        self.expect('[')
        out = []
        self.ws()
        if self.s[self.i:self.i + 1] == ']':
            self.i += 1
            return out
        while True:
            out.append(self.value(',]'))
            self.ws()
            if self.s[self.i:self.i + 1] == ',':
                self.i += 1
                continue
            self.expect(']')
            return out


def _flow(text):
    p = _Flow(text)
    v = p.value('')
    p.ws()
    if p.i != len(p.s):
        raise YamlError(f'unexpected text after value: {text}')
    return v


_KEY = re.compile(r'^("(?:[^"\\]|\\.)*"|\'[^\']*\'|[^\s"\'{\[#][^:]*?)\s*:(?:\s+(.*))?$')


def loads(text):
    lines = []
    for n, raw in enumerate(text.splitlines(), 1):
        if raw.strip() in ('---', '...'):
            continue
        if '\t' in raw[:len(raw) - len(raw.lstrip())]:
            raise YamlError(f'line {n}: tab in indentation')
        s = _strip_comment(raw)
        if s.strip():
            lines.append((len(s) - len(s.lstrip()), s.strip(), n))
    if not lines:
        return None
    value, i = _block(lines, 0, lines[0][0])
    if i != len(lines):
        raise YamlError(f'line {lines[i][2]}: unexpected indentation')
    return value


def _block(lines, i, indent):
    if lines[i][1].startswith('- ') or lines[i][1] == '-':
        return _seq(lines, i, indent)
    return _map(lines, i, indent)


def _map(lines, i, indent, first=None):
    out = {}
    while i < len(lines):
        ind, s, n = lines[i] if first is None else (indent, first, lines[i][2])
        if first is None and ind < indent:
            break
        if ind != indent:
            raise YamlError(f'line {n}: bad indentation')
        m = _KEY.match(s)
        if not m or s.startswith('- '):
            raise YamlError(f'line {n}: expected "key: value", got: {s}')
        key = str(_scalar(m.group(1)))
        rest = m.group(2)
        first = None
        i += 1
        if rest in ('>', '>-', '|', '|-'):
            # block scalar (only plain text without '#'; lines are joined by a space or a newline)
            parts = []
            while i < len(lines) and lines[i][0] > indent:
                parts.append(lines[i][1])
                i += 1
            out[key] = (' ' if rest[0] == '>' else '\n').join(parts)
        elif rest is None or rest == '':
            if i < len(lines) and (lines[i][0] > indent or (lines[i][0] == indent and lines[i][1].startswith('-'))):
                out[key], i = _block(lines, i, lines[i][0])
            else:
                out[key] = None
        else:
            out[key] = _flow(rest)
    return out, i


def _seq(lines, i, indent):
    out = []
    while i < len(lines):
        ind, s, n = lines[i]
        if ind < indent or not (s.startswith('- ') or s == '-'):
            if ind > indent:
                raise YamlError(f'line {n}: bad indentation')
            break
        if ind != indent:
            raise YamlError(f'line {n}: bad indentation')
        item = s[1:].strip()
        if item == '':
            i += 1
            if i < len(lines) and lines[i][0] > indent:
                v, i = _block(lines, i, lines[i][0])
            else:
                v = None
            out.append(v)
        elif item[0] not in '{["\'' and _KEY.match(item):
            # "- key: value" starts a mapping whose other keys are indented to the key column
            col = indent + (len(s) - len(item))
            v, i = _map(lines, i, col, first=item)
            out.append(v)
        else:
            out.append(_flow(item))
            i += 1
    return out, i


def load(path):
    with open(path, encoding='utf-8') as f:
        try:
            return loads(f.read())
        except YamlError as e:
            raise YamlError(f'{path}: {e}') from None


_BARE = re.compile(r'^(?:base|result|param|field|-?\d+)$')


def dump_scalar(v):
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, int):
        return str(v)
    if v is None:
        return 'null'
    s = str(v)
    return s if _BARE.match(s) else json.dumps(s, ensure_ascii=False)


def dump_entry(d):
    """One taint-config entry in the flow style of the hand-written configs."""
    return '{ ' + ', '.join(f'{k}: {dump_scalar(v)}' for k, v in d.items()) + ' }'
