"""A service whose analysis fails says why (out of memory, killed, stack overflow, exit status) and
leaves its log next to the report, in vkr-taint-logs/, which the action uploads with the report.
run: python -I -m unittest discover -s analysis/taint/tests -v"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'scripts')
sys.path.insert(0, SCRIPTS)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # the harness of test_incremental_failures
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # summarize.py reads it at import
import incremental  # noqa: E402
from test_incremental_failures import run  # noqa: E402

OOM_LOG = ['[Pointer analysis] elapsed time: 812.4s',
           'Exception in thread "main" java.lang.OutOfMemoryError: Java heap space',
           '\tat pascal.taie.analysis.pta.core.solver.DefaultSolver.analyze(DefaultSolver.java:200)',
           'exit=1 seconds=845']


class FailureReasonTest(unittest.TestCase):

    def reason(self, lines):
        return incremental.failure_reason(lines, '12g')

    def test_out_of_memory_names_the_heap(self):
        self.assertTrue(self.reason(OOM_LOG).startswith('out of memory: the Java heap (12g) is too small'))

    def test_a_process_killed_for_memory(self):
        reason = self.reason(['[Pointer analysis] ...', 'exit=137 seconds=300'])
        self.assertTrue(reason.startswith('killed (exit 137), most likely for lack of memory'))
        self.assertIn('lower analysis.heap', reason)

    def test_stack_overflow(self):
        self.assertEqual(self.reason(['java.lang.StackOverflowError', 'exit=1 seconds=2']),
                         'stack overflow in the analysis')

    def test_another_failure_gives_the_exit_status(self):
        self.assertEqual(self.reason(['Error: no main class', 'exit=1 seconds=0']), 'analysis failed (exit 1)')
        self.assertEqual(self.reason([]), 'analysis failed (its log has no exit status)')

    def test_an_analysed_class_named_after_the_error_is_not_out_of_memory(self):
        self.assertEqual(self.reason(['class java.lang.OutOfMemoryErrorHandler', 'exit=1 seconds=0']),
                         'analysis failed (exit 1)')


class KeepLogTest(unittest.TestCase):

    def test_the_log_is_copied_next_to_the_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, 'run.log')
            with open(log, 'w', encoding='utf-8') as f:
                f.write('\n'.join(OOM_LOG))
            out = os.path.join(tmp, 'out')
            self.assertEqual(incremental.keep_log(log, out, 'order'), 'vkr-taint-logs/order.log')
            with open(os.path.join(out, 'vkr-taint-logs', 'order.log'), encoding='utf-8') as f:
                self.assertEqual(f.read(), '\n'.join(OOM_LOG))

    def test_a_long_log_keeps_its_end(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(incremental, 'LOG_LIMIT', 10):
            log = os.path.join(tmp, 'run.log')
            with open(log, 'w', encoding='utf-8') as f:
                f.write('0123456789' * 3)
            incremental.keep_log(log, tmp, 'order')
            with open(os.path.join(tmp, 'vkr-taint-logs', 'order.log'), encoding='utf-8') as f:
                self.assertEqual(f.read(), '[the first 20 bytes of the log are left out]\n0123456789')

    def test_no_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(incremental.keep_log(os.path.join(tmp, 'missing.log'), tmp, 'order'))
            self.assertIsNone(incremental.keep_log(None, tmp, 'order'))


class RunTest(unittest.TestCase):

    def test_the_report_points_to_the_log_of_the_failed_service(self):
        logs = {}
        code, report, stdout = run('--no-gate', log='\n'.join(OOM_LOG), logs=logs)
        self.assertEqual(report['services']['order']['log'], 'vkr-taint-logs/order.log')
        self.assertEqual(logs, {'order.log': '\n'.join(OOM_LOG)})
        self.assertIn(os.path.join('vkr-taint-logs', 'order.log').replace(os.sep, '/'), stdout.replace(os.sep, '/'))

    def test_a_service_analysed_now_loses_the_log_of_an_earlier_failure(self):
        logs = {}
        run('--no-gate', stale=('cancel', 'order'), logs=logs)
        self.assertEqual(set(logs), {'order.log'})


class SummaryTest(unittest.TestCase):

    def test_the_job_summary_gives_the_reason_and_the_log(self):
        report = {'chains': [], 'seconds': 0, 'failed': ['order'],
                  'services': {'order': {'failed': 'out of memory: the Java heap (12g) is too small <b>',
                                         'log': 'vkr-taint-logs/order.log', 'seconds': 845}}}
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, 'r.json')
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(report, f)
            out = subprocess.run([sys.executable, '-I', os.path.join(SCRIPTS, 'report_md.py'), path],
                                 capture_output=True, text=True, encoding='utf-8', check=True).stdout
        self.assertIn('> - `order`: out of memory: the Java heap (12g) is too small &lt;b&gt;; its log is '
                      '`vkr-taint-logs/order.log` in the `vkr-taint-report` artifact', out)


if __name__ == '__main__':
    unittest.main()
