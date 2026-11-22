"""SARIF output of a report: sink location, code flow through the endpoints' handlers, baseline state."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # read at import by the scripts
import sarif  # noqa: E402

CHAIN = ('ep:shop-gw:POST /orders <0/note>  --HTTP /api/orders-->  ep:order-service:POST /api/orders <0>  '
         'CWE-89 @ OrderRepository#find:42')
MODEL = {'endpoints': [
    {'id': 'ep:shop-gw:POST /orders', 'handler': {'file': 'shop-gw/src/main/java/gw/OrdersApi.java', 'line': 12}},
    {'id': 'ep:order-service:POST /api/orders',
     'handler': {'file': 'order-service/src/main/java/order/OrderController.java', 'line': 30}}]}


class SarifTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()
        for rel in ('order-service/src/main/java/order/OrderRepository.java',
                    'other-service/src/main/java/x/OrderRepository.java'):
            os.makedirs(os.path.join(self.root, os.path.dirname(rel)), exist_ok=True)
            open(os.path.join(self.root, rel), 'w').close()

    def result(self, baseline=None):
        doc = sarif.to_sarif({'chains': [CHAIN], 'failed': []}, MODEL, self.root, baseline)
        return doc['runs'][0]['results'][0]

    def test_sink_is_located_in_the_module_of_the_last_service(self):
        loc = self.result()['locations'][0]['physicalLocation']
        self.assertEqual(loc['artifactLocation']['uri'], 'order-service/src/main/java/order/OrderRepository.java')
        self.assertEqual(loc['region']['startLine'], 42)

    def test_code_flow_runs_through_each_handler_to_the_sink(self):
        steps = self.result()['codeFlows'][0]['threadFlows'][0]['locations']
        self.assertEqual([s['location']['physicalLocation']['artifactLocation']['uri'] for s in steps],
                         ['shop-gw/src/main/java/gw/OrdersApi.java', 'order-service/src/main/java/order/OrderController.java',
                          'order-service/src/main/java/order/OrderRepository.java'])
        self.assertEqual(self.result()['level'], 'error')

    def test_fingerprint_and_baseline_ignore_line_numbers(self):
        moved = CHAIN.replace(':42', ':57')
        self.assertEqual(self.result({'chains': [moved]})['baselineState'], 'unchanged')
        self.assertEqual(self.result({'chains': []})['baselineState'], 'new')
        doc = sarif.to_sarif({'chains': [moved], 'failed': []}, MODEL, self.root)
        self.assertEqual(doc['runs'][0]['results'][0]['partialFingerprints'], self.result()['partialFingerprints'])

    def test_failed_service_marks_the_run_unsuccessful(self):
        doc = sarif.to_sarif({'chains': [], 'failed': ['order-service']}, MODEL, self.root)
        self.assertFalse(doc['runs'][0]['invocations'][0]['executionSuccessful'])


if __name__ == '__main__':
    unittest.main()
