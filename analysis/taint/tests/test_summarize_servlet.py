"""summarize.py reads the call sources of a servlet application (a request getter inside doPost) and
classifies the sinks of the servlet catalogues: JDBC statement text (CWE-89), the response writer
(CWE-79), an OS command (CWE-78) and a file path (CWE-22)."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, 'scripts'))
os.environ.pop('VKR_DISCOVERY', None)
import summarize  # noqa: E402

SERVLET = 'com.shop.legacy.LegacyServlet'
DO_POST = f'<{SERVLET}: void doPost(javax.servlet.http.HttpServletRequest,javax.servlet.http.HttpServletResponse)>'
MODEL = {'endpoints': [], 'listeners': [], 'publishers': [], 'httpClients': [], 'channels': []}
SRC = f'{DO_POST}[3@L20] $r3 = invokeinterface request.getParameter(%stringconst0)/result'
LOG = '\n'.join([
    f'TaintFlow{{{SRC} -> {DO_POST}[9@L25] $r7 = invokeinterface $r6.executeQuery($r5)/0}}',
    f'TaintFlow{{{SRC} -> {DO_POST}[12@L27] invokevirtual $r8.print($r5)/0}}',
    f'TaintFlow{{{SRC} -> {DO_POST}[15@L29] $r9 = invokevirtual $r10.exec($r5)/0}}',
    f'TaintFlow{{{SRC} -> {DO_POST}[18@L31] $r11 = invokestatic java.nio.file.Paths.get($r5, $r12)/0}}',
]) + '\n'


class ServletSummaryTest(unittest.TestCase):

    def test_call_source_is_an_entry_of_the_servlet_and_each_sink_has_its_cwe(self):
        with tempfile.TemporaryDirectory() as tmp:
            model, log, out = (os.path.join(tmp, f) for f in ('model.json', 'legacy.log', 'legacy.json'))
            json.dump(MODEL, open(model, 'w', encoding='utf-8'))
            open(log, 'w', encoding='utf-8').write(LOG)
            summarize.summary('legacy', log, model, out, tmp, tmp)
            entries = json.load(open(out, encoding='utf-8'))['entries']
        self.assertEqual([(e['entry'], e['input']) for e in entries], [(f'{SERVLET}#doPost', 'getParameter')])
        self.assertEqual(sorted((x['cwe'], x['site']) for x in entries[0]['exits']),
                         [('CWE-22', 'LegacyServlet#doPost:31'), ('CWE-78', 'LegacyServlet#doPost:29'),
                          ('CWE-79', 'LegacyServlet#doPost:27'), ('CWE-89', 'LegacyServlet#doPost:25')])


if __name__ == '__main__':
    unittest.main()
