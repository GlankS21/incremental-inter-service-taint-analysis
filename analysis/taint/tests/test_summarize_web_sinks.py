"""summarize.py classifies the flows of the web catalogues: a response body (vkr.plugin.ResponseBodySink)
is CWE-79 at the return line, a URL opened is CWE-918, a template compiled is CWE-1336."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.pop('VKR_DISCOVERY', None)
import summarize  # noqa: E402

CTRL = 'com.shop.web.PageController'
MODEL = {'endpoints': [{'id': 'ep:web:GET /page', 'service': 'web', 'params': [],
                        'handler': {'file': 'x.java', 'className': CTRL, 'method': 'page', 'line': 10}}],
         'listeners': [], 'publishers': [], 'httpClients': [], 'channels': []}
SRC = f'<{CTRL}: java.lang.String page(java.lang.String)>/0'
LOG = '\n'.join([
    f'ResponseFlow{{{SRC} -> <{CTRL}: java.lang.String page(java.lang.String)>@L14}}',
    f'TaintFlow{{{SRC} -> <com.shop.web.FetchService: java.lang.String fetch(java.lang.String)>[7@L31] '
    f'$r3 = invokevirtual $r2.openConnection()/base}}',
    f'TaintFlow{{{SRC} -> <com.shop.web.Render: java.lang.String render(java.lang.String)>[4@L22] '
    f'$r5 = invokevirtual $r1.process(content, $r2)/0}}',
]) + '\n'


class WebSinkSummaryTest(unittest.TestCase):

    def test_each_web_sink_is_a_local_finding_of_its_cwe(self):
        with tempfile.TemporaryDirectory() as tmp:
            model, log, out = (os.path.join(tmp, f) for f in ('model.json', 'web.log', 'web.json'))
            json.dump(MODEL, open(model, 'w', encoding='utf-8'))
            open(log, 'w', encoding='utf-8').write(LOG)
            summarize.summary('web', log, model, out, tmp, tmp)
            exits = json.load(open(out, encoding='utf-8'))['entries'][0]['exits']
        self.assertEqual(sorted((x['cwe'], x['site']) for x in exits),
                         [('CWE-1336', 'Render#render:22'), ('CWE-79', 'PageController#page:14'),
                          ('CWE-918', 'FetchService#fetch:31')])
        self.assertTrue(all(x['kind'] == 'LOCAL' for x in exits))


if __name__ == '__main__':
    unittest.main()
