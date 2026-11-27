"""Unit tests of report_md.py: text taken from the analysed sources never becomes markdown or HTML in
the job summary.
run: python -I -m unittest discover -s analysis/taint/tests -v"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'scripts')
sys.path.insert(0, SCRIPTS)
import report_md  # noqa: E402

HOSTILE = 'GET /a`![x](https://example.invalid/p.png)<img src=x>\n| injected |'


class CodeSpanTest(unittest.TestCase):
    def test_code_span_cannot_be_closed_or_broken(self):
        out = report_md.code(HOSTILE)
        self.assertTrue(out.startswith('`') and out.endswith('`'))
        self.assertEqual(out.count('`'), 2)
        self.assertNotIn('\n', out)

    def test_hop_wraps_every_name(self):
        out = report_md.hop('HTTP', ('ep', 'svc`x', HOSTILE, None))
        self.assertEqual(out.count('`'), 4)

    def test_cell_keeps_one_table_row(self):
        out = report_md.cell(report_md.code(HOSTILE))
        self.assertNotIn('\n', out)
        self.assertNotIn(' | ', out.replace('\\|', ''))


class DetailsTest(unittest.TestCase):
    def test_removed_findings_are_escaped(self):
        report = {'chains': [], 'findings': [], 'seconds': 0, 'services': {},
                  'new': [], 'removed': [], 'newFindings': [],
                  'removedFindings': ['```\n<img src=x onerror=alert(1)>']}
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, 'r.json')
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(report, f)
            out = subprocess.run([sys.executable, '-I', os.path.join(SCRIPTS, 'report_md.py'), path],
                                 capture_output=True, text=True, encoding='utf-8', check=True).stdout
        self.assertIn('&lt;img src=x onerror=alert(1)&gt;', out)
        self.assertNotIn('<img', out)


class ToolChangedTest(unittest.TestCase):
    """Against a baseline of another version of the tool the summary says why new findings pass."""

    def summary(self, **fields):
        report = {'tool': 'a' * 16, 'chains': [], 'findings': [], 'seconds': 0, 'services': {},
                  'new': [], 'removed': [], 'newFindings': [], 'removedFindings': [], **fields}
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, 'r.json')
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(report, f)
            return subprocess.run([sys.executable, '-I', os.path.join(SCRIPTS, 'report_md.py'), path],
                                  capture_output=True, text=True, encoding='utf-8', check=True).stdout

    def test_a_baseline_of_another_version_is_named(self):
        out = self.summary(baselineTool='b' * 16, toolChanged=True)
        self.assertIn('another version of DeltaTaint** (`bbbbbbbbbbbbbbbb`, now `aaaaaaaaaaaaaaaa`)', out)
        self.assertIn('do not fail the gate', out)

    def test_a_baseline_without_a_version_is_older_than_1_5(self):
        self.assertIn('(version 1.4 or older, now', self.summary(baselineTool=None, toolChanged=True))

    def test_a_baseline_of_this_version_has_no_notice(self):
        self.assertNotIn('another version', self.summary(baselineTool='a' * 16, toolChanged=False))


if __name__ == '__main__':
    unittest.main()
