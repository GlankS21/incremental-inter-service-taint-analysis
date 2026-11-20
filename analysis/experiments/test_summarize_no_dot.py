"""Tests that the CI composition path reads no taint-flow-graph .dot files.

The whole-system runs keep their .dot graphs for the verified-flow comparison; the incremental
pipeline must not depend on them. The behavioural test runs summarize.py's summary and compose
with an open() audit hook and asserts no .dot path is ever opened; the static test asserts the
script itself mentions no .dot file.

usage: pytest test_summarize_no_dot.py
"""
import json
import os
import subprocess
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
SUMMARIZE = os.path.join(HERE, os.pardir, 'taint', 'scripts', 'summarize.py')
SCRIPTS = os.path.normpath(os.path.join(HERE, os.pardir, 'taint', 'scripts'))

DRIVER = '''
import sys
opened = []


def hook(event, args):
    if event == 'open':
        opened.append(str(args[0]))


sys.addaudithook(hook)
sys.path.insert(0, sys.argv[1])
import summarize
sys.argv = sys.argv[:1] + sys.argv[2:]
if sys.argv[1] == 'summary':
    summarize.summary(*sys.argv[2:8])
else:
    summarize.compose(sys.argv[2], sys.argv[3:])
print('OPENED:' + '|'.join(opened))
'''


def run_driver(tmp_path, mode, argv):
    driver = tmp_path / 'driver.py'
    driver.write_text(DRIVER, encoding='utf-8')
    r = subprocess.run([sys.executable, '-I', str(driver), SCRIPTS, mode, *argv],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    assert r.returncode == 0, r.stderr
    opened = [p for p in r.stdout.split('OPENED:')[1].split('|') if p]
    dots = [p for p in opened if p.lower().endswith('.dot')]
    return dots


@pytest.fixture
def tiny_model(tmp_path):
    # two services, one HTTP channel, endpoints with field inputs
    model = {'services': ['svc-a', 'svc-b'],
             'endpoints': [
                 {'id': 'ep:svc-a:POST /pay', 'service': 'svc-a',
                  'handler': {'className': 'a.PayController', 'method': 'pay'}},
                 {'id': 'ep:svc-b:POST /book', 'service': 'svc-b',
                  'handler': {'className': 'b.BookController', 'method': 'book'}}],
             'listeners': [], 'publishers': [], 'httpClients': [
                 {'id': 'http:svc-a:PayClient#book', 'service': 'svc-a', 'targetService': 'svc-b',
                  'declaration': {'className': 'a.PayClient', 'method': 'book'},
                  'callSites': [{'className': 'a.PayService', 'method': 'pay'}]}],
             'channels': [{'kind': 'HTTP', 'from': 'http:svc-a:PayClient#book',
                           'to': 'ep:svc-b:POST /book',
                           'attributes': {'endpointPath': '/book'}}]}
    m = tmp_path / 'model.json'
    m.write_text(json.dumps(model), encoding='utf-8')
    return str(m)


def test_compose_reads_no_dot(tmp_path, tiny_model):
    s1 = {'service': 'svc-a', 'entries': [
        {'entry': 'ep:svc-a:POST /pay', 'input': 'cardNumber',
         'exits': [{'kind': 'CHANNEL', 'channel': 'http:svc-a:PayClient#book', 'arg': '0'}]}]}
    s2 = {'service': 'svc-b', 'entries': [
        {'entry': 'ep:svc-b:POST /book', 'input': 'cardNumber',
         'exits': [{'kind': 'LOCAL', 'cwe': 'CWE-117', 'site': 'b.BookController#book:9'}]}]}
    p1 = tmp_path / 'svc-a.json'
    p1.write_text(json.dumps(s1), encoding='utf-8')
    p2 = tmp_path / 'svc-b.json'
    p2.write_text(json.dumps(s2), encoding='utf-8')
    out = tmp_path / 'composed.txt'
    r = subprocess.run([sys.executable, '-I', SUMMARIZE, 'compose', tiny_model, str(p1), str(p2)],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    assert r.returncode == 0, r.stderr
    assert 'CWE-117' in r.stdout
    # the same composition through the audit-hooked driver opens no .dot
    dots = run_driver(tmp_path, 'compose', [tiny_model, str(p1), str(p2)])
    assert dots == []


def test_summary_reads_no_dot(tmp_path, tiny_model):
    log = tmp_path / 'tai-e.log'
    log.write_text('TaintFlow{<a.PayController: void pay(a.PayReq)>/0/cardNumber '
                   '-> <b.BookController: void book(a.BookReq)>[3@L9] invokestatic '
                   'taint.SourceSink.sink(x)/0}\n', encoding='utf-8')
    app = tmp_path / 'app'
    (app / 'a').mkdir(parents=True)
    common = tmp_path / 'common'
    common.mkdir()
    out = tmp_path / 'summary.json'
    r = subprocess.run([sys.executable, '-I', SUMMARIZE, 'summary', 'svc-a', str(log), tiny_model,
                        str(out), str(app), str(common)], capture_output=True, text=True,
                       encoding='utf-8')
    assert r.returncode == 0, r.stderr
    assert out.exists()
    dots = run_driver(tmp_path, 'summary', ['svc-a', str(log), tiny_model, str(out), str(app),
                                            str(common)])
    assert dots == []


def test_source_mentions_no_dot():
    t = open(SUMMARIZE, encoding='utf-8').read()
    assert '.dot' not in t
