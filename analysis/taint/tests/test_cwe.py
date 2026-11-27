"""The built-in sink catalogues (cwe.py) are the one table the scripts read: every catalogue has sink
methods, is accepted by a vkr-taint.yml, is enabled for a repository without one, is recognised in
the Micans log and has a SARIF rule of its own."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # read at import by the scripts
import cwe  # noqa: E402
import gen_taint_config  # noqa: E402
import incremental  # noqa: E402
import sarif  # noqa: E402
import vkr_config  # noqa: E402
import vkr_taint  # noqa: E402
import vkr_yaml  # noqa: E402

# sink calls as the Micans log prints them (summarize.SINK_CALL: the part after the statement index)
CALLS = {
    'CWE-117': 'invokeinterface $r1.info($r2)',
    'CWE-89': '$r7 = invokeinterface $r6.executeQuery($r5)',
    'CWE-918': '$r3 = invokevirtual $r2.openConnection()',
    'CWE-1336': '$r3 = invokevirtual $r2.process($r4, $r5)',
    'CWE-79': 'invokevirtual $r8.print($r5)',
    'CWE-78': '$r9 = invokevirtual $r10.exec($r5)',
    'CWE-22': '$r11 = invokestatic java.nio.file.Paths.get($r5, $r12)',
}


class CatalogueTableTest(unittest.TestCase):

    def test_every_catalogue_has_its_sink_methods(self):
        self.assertEqual(set(cwe.CATALOGUES), set(gen_taint_config.CATALOGUES))

    def test_a_config_may_list_every_catalogue(self):
        self.assertEqual(vkr_config.KNOWN_SINKS, cwe.CATALOGUES)

    def test_a_repository_without_a_config_gets_every_catalogue(self):
        with tempfile.TemporaryDirectory() as work:
            config = vkr_yaml.load(vkr_taint.write_config(work, work))
        self.assertEqual(config['taint']['sinks'], list(cwe.CATALOGUES))

    def test_the_run_key_covers_the_table(self):
        self.assertIn('cwe.py', incremental.CONFIG_SCRIPTS)


class SinkCallTest(unittest.TestCase):

    def test_each_catalogue_recognises_its_sink_call(self):
        self.assertEqual({c: cwe.of_call(call) for c, call in CALLS.items()}, {c: c for c in CALLS})
        self.assertEqual(set(CALLS), set(cwe.BY_ID))

    def test_a_call_of_no_catalogue_has_no_cwe(self):
        self.assertIsNone(cwe.of_call('$r3 = invokeinterface $r2.lookup($r1)'))

    def test_a_mybatis_mapper_method_runs_sql_whatever_its_name(self):
        self.assertEqual(cwe.of_call('$r3 = invokeinterface $r2.findByName($r1)', sql=True), 'CWE-89')

    def test_a_log_call_stays_a_log_sink(self):
        self.assertEqual(cwe.of_call('invokeinterface $r1.error($r2)', sql=True), 'CWE-117')

    def test_a_constructor_is_a_sink_of_the_class_the_method_constructs(self):
        self.assertEqual(cwe.of_constructor({'java/lang/ProcessBuilder', 'java/lang/StringBuilder'}), 'CWE-78')
        self.assertEqual(cwe.of_constructor({'java/io/FileWriter'}), 'CWE-22')
        self.assertEqual(cwe.of_constructor({'freemarker/template/Template'}), 'CWE-1336')
        self.assertIsNone(cwe.of_constructor({'java/lang/StringBuilder'}))


class SarifRuleTest(unittest.TestCase):

    def test_every_catalogue_has_a_named_rule_with_its_severity(self):
        for c in cwe.CWES:
            r = sarif.rule(c.id)
            self.assertEqual((r['name'], r['shortDescription']['text'], r['properties']['security-severity']),
                             (c.rule, c.title, c.severity))
            self.assertNotEqual(r['fullDescription']['text'], c.id)

    def test_command_injection_is_an_error(self):
        chain = 'ep:files:GET /run <0>  CWE-78 @ Runner#run:12'
        result = sarif.to_sarif({'chains': [chain]}, {}, tempfile.gettempdir())['runs'][0]['results'][0]
        self.assertEqual(result['level'], 'error')
        self.assertTrue(result['message']['text'].startswith('OS command injection: '))

    def test_a_cwe_of_no_catalogue_keeps_its_id(self):
        r = sarif.rule('CWE-643')
        self.assertEqual((r['name'], r['properties']['security-severity']), ('CWE-643', '5.0'))


if __name__ == '__main__':
    unittest.main()
