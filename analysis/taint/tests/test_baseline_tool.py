"""A report records the version of the tool that made it (incremental.tool_key), and the gate does not
fail on new findings against a baseline of another version: a finding the baseline lacks may then
come from the tool rather than from the change. A failed service still fails the gate.
run: python -I -m unittest discover -s analysis/taint/tests -v"""
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # the harness of test_incremental_failures
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # summarize.py reads it at import
import incremental  # noqa: E402
from test_incremental_failures import LOCAL, TOOL, run  # noqa: E402

NEW = incremental.finding_key(LOCAL)  # "cancel" has it, an empty baseline does not


class GateTest(unittest.TestCase):

    def test_a_new_finding_fails_the_gate_against_a_baseline_of_the_same_version(self):
        code, report, _ = run('--gate', baseline={'tool': TOOL, 'chains': []}, failing=())
        self.assertEqual(code, 1)
        self.assertEqual(report['newFindings'], [NEW])
        self.assertEqual((report['tool'], report['baselineTool'], report['toolChanged']), (TOOL, TOOL, False))

    def test_a_baseline_of_version_1_4_or_older_does_not_fail_the_gate(self):
        code, report, stdout = run('--gate', baseline={'chains': []}, failing=())
        self.assertEqual(code, 0)
        self.assertEqual(report['newFindings'], [NEW])
        self.assertEqual((report['baselineTool'], report['toolChanged']), (None, True))
        self.assertIn('another version of the tool (unknown', stdout)

    def test_a_baseline_of_another_version_does_not_fail_the_gate(self):
        code, report, stdout = run('--gate', baseline={'tool': 'o' * 16, 'chains': []}, failing=())
        self.assertEqual(code, 0)
        self.assertEqual((report['baselineTool'], report['toolChanged']), ('o' * 16, True))
        self.assertIn(f'({"o" * 16}, now {TOOL})', stdout)

    def test_a_failed_service_fails_the_gate_against_any_baseline(self):
        code, report, _ = run('--gate', baseline={'chains': []})
        self.assertEqual(code, 2)
        self.assertTrue(report['toolChanged'])


class ToolKeyTest(unittest.TestCase):
    """tool_key on a copy of the tool's layout: analysis/taint and the two jars and the JDK beside it."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = self.root = self.tmp.name
        for s in incremental.CONFIG_SCRIPTS + ['summarize.py', 'incremental.py']:
            self.write(f'scripts/{s}', f'# {s}\n')
        for f in ('launcher/src/vkr/A.java', 'jdk-stubs/java/B.java', 'servlet-stubs/javax/C.java'):
            self.write(f, f'class {f[-6]} {{}}\n')
        self.write('jdk/release', 'JAVA_VERSION="24.0.1"\n')
        self.micans, self.extractor = os.path.join(d, 'micans.jar'), os.path.join(d, 'extractor.jar')
        self.jar(self.micans, {'pascal/taie/Main.class': b'main'})
        self.jar(self.extractor, {'ru/itmo/Main.class': b'main'})

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text):
        p = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w', encoding='utf-8') as f:
            f.write(text)

    @staticmethod
    def jar(path, entries, date_time=(2026, 1, 1, 0, 0, 0)):
        with zipfile.ZipFile(path, 'w') as z:
            for name, data in entries.items():
                z.writestr(zipfile.ZipInfo(name, date_time), data)

    def key(self):
        return incremental.tool_key(self.root, self.micans, self.extractor, os.path.join(self.root, 'jdk'))

    def test_the_key_is_stable(self):
        self.assertEqual(self.key(), self.key())
        self.assertRegex(self.key(), r'^[0-9a-f]{16}$')

    def test_a_jar_rebuilt_from_the_same_classes_keeps_the_key(self):
        before = self.key()
        self.jar(self.micans, {'pascal/taie/Main.class': b'main'}, date_time=(2026, 6, 1, 12, 0, 0))
        self.jar(self.extractor, {'ru/itmo/Main.class': b'main'}, date_time=(2026, 6, 1, 12, 0, 0))
        self.assertEqual(self.key(), before)

    def test_each_part_of_the_tool_changes_the_key(self):
        changes = {
            'launcher plugin': lambda: self.write('launcher/src/vkr/A.java', 'class A { int x; }\n'),
            'config script': lambda: self.write('scripts/cwe.py', '# cwe.py, another sink\n'),
            'summaries': lambda: self.write('scripts/summarize.py', '# summarize.py, another exit\n'),
            'composition': lambda: self.write('scripts/incremental.py', '# incremental.py, another chain\n'),
            'stubs': lambda: self.write('servlet-stubs/javax/D.java', 'class D {}\n'),
            'micans': lambda: self.jar(self.micans, {'pascal/taie/Main.class': b'patched'}),
            'channel-extractor': lambda: self.jar(self.extractor, {'ru/itmo/Main.class': b'kafka'}),
            'jdk': lambda: self.write('jdk/release', 'JAVA_VERSION="24.0.2"\n'),
        }
        keys = {'before': self.key()}
        for part, change in changes.items():
            change()
            keys[part] = self.key()
        self.assertEqual(len(set(keys.values())), len(keys), keys)

    def test_a_missing_extractor_jar_is_left_out(self):
        os.remove(self.extractor)
        self.assertRegex(self.key(), r'^[0-9a-f]{16}$')


if __name__ == '__main__':
    unittest.main()
