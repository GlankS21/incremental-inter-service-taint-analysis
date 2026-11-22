"""A flow from an overloaded handler goes only to the endpoint whose handler has that many parameters.

mall-admin PmsBrandController has getList() (GET /brand/listAll) and getList(keyword, pageNum,
pageSize, sort) (GET /brand/list); a flow from parameter 3 must not be reported for /brand/listAll.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.setdefault('JAVA_HOME', os.path.dirname(os.path.abspath(__file__)))  # summarize.py reads it at import
import summarize  # noqa: E402

CTRL = 'shop.web.BrandController'


def endpoint(path, params, line):
    return {'id': f'ep:admin:GET {path}', 'params': [{'name': p} for p in params],
            'handler': {'className': CTRL, 'method': 'getList', 'line': line}}


class OverloadTest(unittest.TestCase):

    def setUp(self):
        self.index = summarize.by_handler(
            [endpoint('/brand/listAll', [], 29), endpoint('/brand/list', ['keyword', 'pageNum', 'pageSize', 'sort'], 77)],
            'handler')

    def ids(self, argc):
        return [h['id'] for h in summarize.handlers_of(self.index, CTRL, 'getList', argc)]

    def test_overloads_are_told_apart_by_parameter_count(self):
        self.assertEqual(self.ids(4), ['ep:admin:GET /brand/list'])
        self.assertEqual(self.ids(0), ['ep:admin:GET /brand/listAll'])

    def test_no_exact_match_keeps_every_overload(self):
        self.assertEqual(len(self.ids(5)), 2)  # e.g. an extra HttpServletRequest the model does not list

    def test_source_signature_is_parsed(self):
        m = summarize.PARAM_SRC.match(
            f'<{CTRL}: R getList(java.lang.String,java.lang.Integer,java.lang.Integer,java.lang.String)>/3')
        self.assertEqual(m.groups()[1:], ('getList', 'java.lang.String,java.lang.Integer,java.lang.Integer,java.lang.String', '3'))


if __name__ == '__main__':
    unittest.main()
