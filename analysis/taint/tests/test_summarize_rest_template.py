"""summarize.py maps a flow into a RestTemplate call to the client of that call statement.

channel-extractor records a RestTemplate client per caller of the helper that wraps the call: the
declaration is the call statement, the call site is the caller. A flow reported at the statement
reaches every such client (the summary has no calling context); the composition then links the
caller's entry point across the HTTP channel.
"""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.pop('VKR_DISCOVERY', None)
import summarize  # noqa: E402


def ref(cls, method, line):
    return {'file': 'x.java', 'className': cls, 'method': method, 'line': line}


HELPER = ref('com.shop.cancel.CancelService', 'getOrder', 313)
MODEL = {
    'endpoints': [
        {'id': 'ep:cancel:GET /refund/{id}', 'service': 'cancel', 'params': [],
         'handler': ref('com.shop.cancel.CancelController', 'refund', 30)},
        {'id': 'ep:order:GET /order/{id}', 'service': 'order', 'params': [],
         'handler': ref('com.shop.order.OrderController', 'get', 90)}],
    'listeners': [], 'publishers': [],
    'httpClients': [
        {'id': f'http:cancel:CancelService#{caller}:{line}:GET order/order/{{?}}', 'service': 'cancel',
         'targetService': 'order', 'clientKind': 'REST_TEMPLATE', 'params': [],
         'declaration': HELPER, 'callSites': [ref('com.shop.cancel.CancelService', caller, line)]}
        for caller, line in (('refund', 57), ('cancel', 115))],
    'channels': [
        {'kind': 'HTTP', 'from': f'http:cancel:CancelService#{caller}:{line}:GET order/order/{{?}}',
         'to': 'ep:order:GET /order/{id}', 'attributes': {'endpointPath': '/order/{id}'}}
        for caller, line in (('refund', 57), ('cancel', 115))],
}
LOG = {
    'cancel': 'TaintFlow{<com.shop.cancel.CancelController: java.lang.Object refund(java.lang.String)>/0 -> '
              '<com.shop.cancel.CancelService: java.lang.Object getOrder(java.lang.String)>[21@L313] '
              '$r16 = invokevirtual $r8.exchange($r12, $r14, $r4, $r13, $r15)/0}\n',
    'order': 'TaintFlow{<com.shop.order.OrderController: java.lang.Object get(java.lang.String)>/0 -> '
             '<com.shop.order.OrderController: java.lang.Object get(java.lang.String)>[2@L91] '
             'invokeinterface $r1.info(%stringconst0, id)/1}\n',
}


class RestTemplateSummaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.model = os.path.join(self.tmp.name, 'model.json')
        with open(self.model, 'w', encoding='utf-8') as f:
            json.dump(MODEL, f)

    def summary(self, service):
        log = os.path.join(self.tmp.name, f'{service}.log')
        out = os.path.join(self.tmp.name, f'{service}.json')
        with open(log, 'w', encoding='utf-8') as f:
            f.write(LOG[service])
        summarize.summary(service, log, self.model, out, self.tmp.name, self.tmp.name)
        return out

    def test_flow_at_the_call_statement_reaches_every_caller_client(self):
        with open(self.summary('cancel'), encoding='utf-8') as f:
            exits = json.load(f)['entries'][0]['exits']
        self.assertEqual(sorted(x['channel'] for x in exits),
                         ['http:cancel:CancelService#cancel:115:GET order/order/{?}',
                          'http:cancel:CancelService#refund:57:GET order/order/{?}'])
        self.assertTrue(all(x['kind'] == 'CHANNEL' for x in exits))

    def test_composition_crosses_the_http_channel(self):
        paths = [self.summary('cancel'), self.summary('order')]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            summarize.compose(self.model, paths)
        chains = [l for l in out.getvalue().splitlines() if '-->' in l]
        self.assertTrue(chains)
        self.assertTrue(all(c.startswith('ep:cancel:GET /refund/{id} <0>  --HTTP /order/{id}-->  '
                                         'ep:order:GET /order/{id} <0>  CWE-117') for c in chains))


if __name__ == '__main__':
    unittest.main()
