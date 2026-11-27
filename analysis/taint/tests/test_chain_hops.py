"""A chain crosses services only when the service changes: a DB hop of one service (a row stored by
one request and read by another) is counted and reported as going through the database, not as a
service call. run: python -I -m unittest discover -s analysis/taint/tests -v"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'scripts')
sys.path.insert(0, SCRIPTS)
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # summarize.py reads it at import
import incremental  # noqa: E402
import sarif  # noqa: E402

LOCAL = 'ep:cart-service:POST /api/cart/items <0>  CWE-117 @ CartController#add:40'
HTTP = ('ep:cancel:GET /refund/{id} <0>  --HTTP /order/{id}-->  ep:order:GET /order/{id} <0>  '
        'CWE-117 @ OrderController#get:100')
# the testbed's base commit: every chain with a hop is one of these
DB = ('ep:order-service:POST /api/orders/{orderId}/cancel <1/reason>  --DB Order [Order.cancellationReason]-->  '
      'db:order-service:Order <Order.cancellationReason>  CWE-117 @ OrderLifecycle#cancel:121')
SHARED_TABLE = ('ep:admin:POST /notes <0>  --DB Note [Note.text]-->  db:report:Note <Note.text>  '
                'CWE-89 @ ReportRepository#search:12')
HTTP_THEN_DB = ('ep:gw:POST /orders <0/note>  --HTTP /api/orders-->  ep:order-service:POST /api/orders <0/note>  '
                '--DB Order [Order.note]-->  db:order-service:Order <Order.note>  CWE-89 @ OrderQuery#find:7')


class ChainServicesTest(unittest.TestCase):

    def test_services_in_order(self):
        self.assertEqual([incremental.chain_services(c) for c in (LOCAL, HTTP, DB, SHARED_TABLE, HTTP_THEN_DB)],
                         [['cart-service'], ['cancel', 'order'], ['order-service'], ['admin', 'report'],
                          ['gw', 'order-service']])

    def test_a_db_hop_within_a_service_does_not_cross_services(self):
        self.assertEqual([incremental.crosses_services(c) for c in (LOCAL, HTTP, DB, SHARED_TABLE, HTTP_THEN_DB)],
                         [False, True, False, True, True])

    def test_through_the_database(self):
        self.assertEqual([incremental.through_database(c) for c in (LOCAL, HTTP, DB, SHARED_TABLE, HTTP_THEN_DB)],
                         [False, False, True, True, True])


class SarifTest(unittest.TestCase):

    def result(self, chain):
        return sarif.to_sarif({'chains': [chain]}, {}, tempfile.gettempdir())['runs'][0]['results'][0]

    def test_a_db_hop_of_one_service_is_no_service_call(self):
        r = self.result(DB)
        self.assertNotIn('service call', r['message']['text'])
        self.assertIn('reaches OrderLifecycle#cancel through the database (Order.cancellationReason)',
                      r['message']['text'])
        self.assertEqual((r['properties']['crossService'], r['properties']['throughDatabase']), (False, True))

    def test_an_http_call_crosses_services(self):
        r = self.result(HTTP)
        self.assertIn('across 1 service call(s): cancel -> order', r['message']['text'])
        self.assertEqual((r['properties']['crossService'], r['properties']['throughDatabase']), (True, False))

    def test_a_call_then_a_stored_field(self):
        text = self.result(HTTP_THEN_DB)['message']['text']
        self.assertIn('across 1 service call(s): gw -> order-service through the database (Order.note)', text)


class SummaryTest(unittest.TestCase):

    def test_the_job_summary_counts_service_calls_and_db_hops_apart(self):
        report = {'chains': [LOCAL, HTTP, DB], 'seconds': 0, 'services': {}}
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, 'r.json')
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(report, f)
            out = subprocess.run([sys.executable, '-I', os.path.join(SCRIPTS, 'report_md.py'), path],
                                 capture_output=True, text=True, encoding='utf-8', check=True).stdout
        self.assertIn('(3 chains to a sink: 1 across services, 1 through the database)', out)


if __name__ == '__main__':
    unittest.main()
