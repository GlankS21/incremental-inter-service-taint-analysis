"""incremental.py goes on when one service fails and reports it.

The services are stubbed below the orchestration (config generation, the Micans run and the summary);
the composition runs for real on a two-service HTTP model. The service "order" fails its analysis:
the run still analyses "cancel", lists "order" under "failed", a baseline chain through "order" goes
to "unchecked" (not "removed"), and with --gate the run exits 2.
"""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # summarize.py reads it at import
import incremental  # noqa: E402

CLIENT = 'http:cancel:CancelService#refund:57:GET order/{?}'
MODEL = {
    'endpoints': [], 'listeners': [], 'publishers': [], 'httpClients': [],
    'channels': [{'kind': 'HTTP', 'from': CLIENT, 'to': 'ep:order:GET /order/{id}',
                  'attributes': {'endpointPath': '/order/{id}'}}],
}
CANCEL = {'service': 'cancel', 'droppedLibrarySinks': 0, 'entries': [
    {'entry': 'ep:cancel:GET /refund/{id}', 'input': '0', 'exits': [
        {'kind': 'LOCAL', 'cwe': 'CWE-117', 'site': 'CancelController#refund:31'},
        {'kind': 'CHANNEL', 'channel': CLIENT, 'arg': 0}]}]}
LOCAL = 'ep:cancel:GET /refund/{id} <0>  CWE-117 @ CancelController#refund:31'
THROUGH_ORDER = ('ep:cancel:GET /refund/{id} <0>  --HTTP /order/{id}-->  ep:order:GET /order/{id} <0>  '
                 'CWE-117 @ OrderController#get:100')


class FailedServiceTest(unittest.TestCase):

    def run_main(self, *args):
        with tempfile.TemporaryDirectory() as tmp:
            model = os.path.join(tmp, 'channels.json')
            with open(model, 'w', encoding='utf-8') as f:
                json.dump(MODEL, f)
            baseline = os.path.join(tmp, 'baseline.json')
            with open(baseline, 'w', encoding='utf-8') as f:
                json.dump({'chains': [LOCAL, THROUGH_ORDER]}, f)
            out = os.path.join(tmp, 'report.json')
            cfg = {'notes': [], 'services': [{'name': 'cancel'}, {'name': 'order'}],
                   'gate': {'baseline': None, 'fail_on': 'none'}}

            def analyse(svc, rkey, flows, base, discovery):
                if svc == 'order':
                    raise incremental.ServiceFailed('analysis failed', os.path.join(tmp, 'order', 'run.log'))
                open(flows, 'w').close()

            def summarize(svc, rkey, flows, model_path, summary, base, discovery):
                with open(summary, 'w', encoding='utf-8') as f:
                    json.dump(CANCEL, f)

            patches = [
                mock.patch.object(incremental, 'WORK', tmp),
                mock.patch.object(incremental, 'CACHE', os.path.join(tmp, 'cache')),
                mock.patch.object(incremental.vkr_config, 'load', return_value=cfg),
                mock.patch.object(incremental.vkr_prepare, 'module'),
                mock.patch.object(incremental.vkr_prepare, 'base_config', return_value=('base.yml', 'disc.json')),
                mock.patch.object(incremental.vkr_prepare, 'tool_env', return_value=dict(os.environ)),
                mock.patch.object(incremental, 'compile_launcher'),
                mock.patch.object(incremental, 'run_key', side_effect=lambda svc, b, d: (svc * 16)[:16]),
                mock.patch.object(incremental, 'summary_key', return_value='s' * 16),
                mock.patch.object(incremental, 'analyse', side_effect=analyse),
                mock.patch.object(incremental, 'summarize', side_effect=summarize),
                mock.patch.object(sys, 'argv', ['incremental.py', '--model', model, '--baseline', baseline,
                                                '--out', out, *args]),
            ]
            code, stdout = 0, io.StringIO()
            with contextlib.ExitStack() as stack:
                for p in patches:
                    stack.enter_context(p)
                stack.enter_context(contextlib.redirect_stdout(stdout))
                try:
                    incremental.main()
                except SystemExit as e:
                    code = e.code
            with open(out, encoding='utf-8') as f:
                return code, json.load(f), stdout.getvalue()

    def test_run_goes_on_and_reports_the_failed_service(self):
        code, report, stdout = self.run_main('--no-gate')
        self.assertEqual(code, 0)
        self.assertEqual(report['failed'], ['order'])
        self.assertEqual(report['services']['order']['failed'], 'analysis failed')
        self.assertFalse(report['services']['cancel']['runCached'])
        self.assertIn(LOCAL, report['chains'])
        self.assertEqual(report['removed'], [])
        self.assertEqual(report['unchecked'], [THROUGH_ORDER])
        self.assertIn('1 of 2 services FAILED', stdout)

    def test_gate_fails_an_incomplete_run(self):
        code, _, _ = self.run_main('--gate')
        self.assertEqual(code, 2)

    def test_failed_services_in_matches_whole_service_names(self):
        self.assertEqual(incremental.failed_services_in(THROUGH_ORDER, ['order', 'cancel']), ['cancel', 'order'])
        self.assertEqual(incremental.failed_services_in(THROUGH_ORDER, ['der']), [])
        self.assertEqual(incremental.failed_services_in('lis:order:q <x>  CWE-89 @ A#b:1', ['order']), ['order'])


if __name__ == '__main__':
    unittest.main()
