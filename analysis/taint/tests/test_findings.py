"""Findings: the chains of one entry point and one sink method, whatever the source label or the path
through the channels (the four chains of the seeded V02 SQL injection are two findings)."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # read at import by the scripts
import incremental  # noqa: E402
import sarif  # noqa: E402

HOP = '  --HTTP /api/inventory/availability/by-warehouse-->  ep:inventory-service:GET /api/inventory/availability/by-warehouse <1>'
SINK = '  CWE-89 @ InventoryServiceImpl#getAvailabilityIn:72'
CART = 'ep:cart-service:POST /api/cart/items'
V02 = [f'{CART} <0/warehouse>{HOP}{SINK}', f'{CART} <0>{HOP}{SINK}',
       f'{CART} <AddCartItemRequest.warehouse>{HOP}{SINK}',
       f'ep:inventory-service:GET /api/inventory/availability/by-warehouse <1>{SINK}']


class FindingsTest(unittest.TestCase):

    def test_source_labels_of_one_entry_point_are_one_finding(self):
        groups = incremental.group_findings(V02)
        self.assertEqual([len(g['chains']) for g in groups], [3, 1])
        self.assertEqual(groups[0]['key'], f'{CART} => CWE-89 @ InventoryServiceImpl#getAvailabilityIn')

    def test_another_path_to_the_same_sink_is_the_same_finding(self):
        other = f'{CART} <0>  --AMQP stock.check-->  ls:inventory-service:onCheck <0/warehouse>{SINK}'
        self.assertEqual(incremental.finding_key(other), incremental.finding_key(V02[0]))

    def test_another_sink_method_or_cwe_is_another_finding(self):
        keys = {incremental.finding_key(V02[1]), incremental.finding_key(V02[1].replace('getAvailabilityIn', 'count')),
                incremental.finding_key(V02[1].replace('CWE-89', 'CWE-117'))}
        self.assertEqual(len(keys), 3)

    def test_sink_line_does_not_change_the_finding(self):
        self.assertEqual(incremental.finding_key(V02[0]), incremental.finding_key(V02[0].replace(':72', ':90')))

    def test_sarif_has_one_result_per_finding_with_every_source_label(self):
        doc = sarif.to_sarif({'chains': V02, 'failed': []}, {}, tempfile.mkdtemp())
        results = doc['runs'][0]['results']
        self.assertEqual(len(results), 2)
        cart = next(r for r in results if r['properties']['finding'].startswith(CART))
        self.assertEqual(len(cart['properties']['chains']), 3)
        for label in ('0/warehouse', 'AddCartItemRequest.warehouse'):
            self.assertIn(label, cart['message']['text'])

    def test_baseline_with_a_known_finding_marks_a_new_label_unchanged(self):
        doc = sarif.to_sarif({'chains': V02, 'failed': []}, {}, tempfile.mkdtemp(), {'chains': V02[1:2]})
        states = {r['properties']['finding'].split(' ')[0]: r['baselineState'] for r in doc['runs'][0]['results']}
        self.assertEqual(states, {'ep:cart-service:POST': 'unchanged', 'ep:inventory-service:GET': 'new'})


if __name__ == '__main__':
    unittest.main()
