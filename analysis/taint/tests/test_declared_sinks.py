"""A sink the configuration declares (a catalogue file of taint.sinks, the sinks of the overrides) is
reported, with the CWE it names (cwe:, on the sink or the file) or CWE-74, instead of being dropped
when its method name is no catalogue's; a declared sink of the call's own class wins over a catalogue
whose name pattern the call happens to match.
run: python -I -m unittest discover -s analysis/taint/tests -v"""
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.pop('VKR_DISCOVERY', None)
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # summarize.py reads it at import
import cwe  # noqa: E402
import sarif  # noqa: E402
import summarize  # noqa: E402
import vkr_config  # noqa: E402

CTRL = 'com.shop.web.DirectoryController'
MODEL = {'endpoints': [{'id': 'ep:web:GET /user', 'service': 'web', 'params': [],
                        'handler': {'file': 'x.java', 'className': CTRL, 'method': 'user', 'line': 10}}],
         'listeners': [], 'publishers': [], 'httpClients': [], 'channels': []}
SRC = f'<{CTRL}: java.lang.String user(java.lang.String)>/0'
LOOKUP = (f'TaintFlow{{{SRC} -> <com.shop.web.Directory: java.lang.Object find(java.lang.String)>[5@L21] '
          f'$r3 = invokeinterface $r2.lookup($r1)/0}}')
EVALUATE = (f'TaintFlow{{{SRC} -> <com.shop.web.Query: java.lang.String run(java.lang.String)>[6@L33] '
            f'$r4 = invokeinterface $r3.evaluate($r1, $r2)/0}}')
CONTEXT = {'method': '<javax.naming.Context: java.lang.Object lookup(java.lang.String)>', 'index': 0}
XPATH = {'method': '<javax.xml.xpath.XPath: java.lang.String evaluate(java.lang.String,java.lang.Object)>',
         'index': 0}
JAVAP_RUN = '''public class com.shop.web.Query {
  public java.lang.String run(java.lang.String);
    Code:
       6: invokeinterface #12,  3   // InterfaceMethod javax/xml/xpath/XPath.evaluate:(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
      11: areturn
}
'''


def exits(log_lines, declared):
    """The exits of the summary of a one-endpoint service whose Micans log is log_lines, with these
    declared sinks (cwe.declared entries)."""
    table = {}
    for s in declared:
        table.setdefault(s['name'], []).append((s['class'].replace('.', '/'), s['cwe']))
    with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(summarize.DECLARED_SINKS, table, clear=True):
        model, log, out = (os.path.join(tmp, f) for f in ('model.json', 'web.log', 'web.json'))
        with open(model, 'w', encoding='utf-8') as f:
            json.dump(MODEL, f)
        with open(log, 'w', encoding='utf-8') as f:
            f.write('\n'.join(log_lines) + '\n')
        summarize.summary('web', log, model, out, tmp, tmp)
        with open(out, encoding='utf-8') as f:
            entries = json.load(f)['entries']
    return [(x['kind'], x.get('cwe')) for e in entries for x in e['exits']]


class DeclaredTest(unittest.TestCase):

    def test_an_entry_names_its_cwe_else_the_file_does(self):
        self.assertEqual(cwe.declared([CONTEXT, {**XPATH, 'cwe': 'cwe-643'}], 'CWE-90'),
                         [{'class': 'javax.naming.Context', 'name': 'lookup', 'cwe': 'CWE-90'},
                          {'class': 'javax.xml.xpath.XPath', 'name': 'evaluate', 'cwe': 'CWE-643'}])
        self.assertEqual(cwe.declared([{'method': '<a.B^: void <init>(java.lang.String)>'}]),
                         [{'class': 'a.B', 'name': '<init>', 'cwe': None}])

    def test_normalize(self):
        self.assertEqual([cwe.normalize(v) for v in ('cwe-90', 'CWE-90', ' CWE-643 ', 'XPath', '90')],
                         ['CWE-90', 'CWE-90', 'CWE-643', None, None])


class SummaryTest(unittest.TestCase):

    def test_a_call_of_no_catalogue_was_dropped(self):
        self.assertEqual(exits([LOOKUP], []), [('UNKNOWN', None)])

    def test_a_declared_sink_without_a_cwe_is_cwe_74(self):
        self.assertEqual(exits([LOOKUP], cwe.declared([CONTEXT])), [('LOCAL', 'CWE-74')])

    def test_a_declared_sink_has_the_cwe_it_names(self):
        self.assertEqual(exits([LOOKUP], cwe.declared([{**CONTEXT, 'cwe': 'CWE-90'}])), [('LOCAL', 'CWE-90')])

    def test_a_catalogue_keeps_a_call_whose_class_is_unknown(self):
        # without the classes javap cannot tell XPath.evaluate from a template engine's evaluate
        self.assertEqual(exits([EVALUATE], cwe.declared([{**XPATH, 'cwe': 'CWE-643'}])), [('LOCAL', 'CWE-1336')])

    def test_a_declared_sink_of_the_calls_class_wins(self):
        with mock.patch.dict(summarize._javap_cache):
            with tempfile.TemporaryDirectory() as tmp:
                summarize._javap_cache[(tmp, 'com.shop.web.Query', '-c')] = JAVAP_RUN
                self.assertEqual(summarize.invoked(tmp, 'com.shop.web.Query', 'run', 'evaluate'),
                                 {'javax/xml/xpath/XPath'})
                with mock.patch.object(summarize, 'invoked', return_value={'javax/xml/xpath/XPath'}):
                    self.assertEqual(exits([EVALUATE], cwe.declared([{**XPATH, 'cwe': 'CWE-643'}])),
                                     [('LOCAL', 'CWE-643')])
                    self.assertEqual(exits([EVALUATE], cwe.declared([XPATH])), [('LOCAL', 'CWE-1336')])

    def test_declared_cwe(self):
        ctx = [('javax/naming/Context', None)]
        self.assertIsNone(summarize.declared_cwe([], set(), None))
        self.assertEqual(summarize.declared_cwe([], set(), 'CWE-89'), 'CWE-89')
        self.assertEqual(summarize.declared_cwe(ctx, {'javax/naming/InitialContext'}, None), 'CWE-74')
        self.assertEqual(summarize.declared_cwe(ctx, {'javax/naming/InitialContext'}, 'CWE-89'), 'CWE-89')
        self.assertEqual(summarize.declared_cwe([('javax/naming/Context', 'CWE-90')], {'javax/naming/Context'},
                                                'CWE-89'), 'CWE-90')


class ConfigTest(unittest.TestCase):

    def write(self, tmp, name, text):
        p = os.path.join(tmp, name)
        with open(p, 'w', encoding='utf-8') as f:
            f.write(text)
        return p

    def test_a_catalogue_file_with_a_bad_cwe_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok = self.write(tmp, 'ok.yml', 'cwe: CWE-90\nsinks:\n  - { method: "<a.B: void c(int)>", index: 0, cwe: cwe-643 }\n')
            bad = self.write(tmp, 'bad.yml', 'cwe: LDAP\nsinks:\n  - { method: "<a.B: void c(int)>", index: 0, cwe: 643 }\n')
            self.assertEqual(vkr_config.sink_cwe_errors(ok), [])
            self.assertEqual(vkr_config.sink_cwe_errors(bad), ["cwe must look like CWE-89, got 'LDAP'",
                                                               "sinks[0]: cwe must look like CWE-89, got 643"])

    def test_an_override_sink_with_a_bad_cwe_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = self.write(tmp, 'o.yml', 'sinks:\n  - { method: "<a.B: void c(int)>", index: 0, cwe: XPath }\n')
            with self.assertRaisesRegex(vkr_config.ConfigError, r"sinks\[0\]: cwe must look like CWE-89, got 'XPath'"):
                vkr_config.load_overrides(p)


class SarifTest(unittest.TestCase):

    def test_a_declared_sink_has_a_rule_of_its_own(self):
        r = sarif.rule(cwe.DECLARED)
        self.assertEqual((r['name'], r['shortDescription']['text']), ('DeclaredSink', 'Tainted data reaches a declared sink'))
        chain = 'ep:web:GET /user <0>  CWE-74 @ Directory#find:21'
        result = sarif.to_sarif({'chains': [chain]}, {}, tempfile.gettempdir())['runs'][0]['results'][0]
        self.assertTrue(result['message']['text'].startswith('Tainted data reaches a declared sink: GET /user'))


if __name__ == '__main__':
    unittest.main()
