"""Regression tests for the basemall composition (micans_compose.py).

Pins the goods -> shops composition of verified flow taint-flow-18: a Feign call carrying a value
sourced at a web-endpoint DTO field must be chained into the receiving endpoint's input and its
local sink, both with the invoked method's signature in the ChannelFlow (ChannelArgSink since
75df68d) and without it (older logs, matched by method name). Flows whose services are connected
only through shared library code (the context-insensitivity artefacts 31 and 44 of the whole-system
run) must stay out, and so must chains rooted at a listener.

usage: pytest test_micans_compose.py
"""
import json

import micans_compose as mc

SOURCE = ('<com.medusa.gruul.goods.web.controller.manager.SaleModeController: '
          'com.medusa.gruul.common.core.util.Result updateSaleMode('
          'com.medusa.gruul.goods.api.model.dto.manager.SaleModeDto)>')
CALLER = ('<com.medusa.gruul.goods.service.manager.impl.SaleModeServiceImpl: '
          'void updateSaleMode(com.medusa.gruul.goods.api.model.dto.manager.SaleModeDto)>')
CALLER_CLS = 'com.medusa.gruul.goods.service.manager.impl.SaleModeServiceImpl'
CLIENT = 'com.medusa.gruul.shops.api.feign.RemoteShopsService'
REMOTE = ('<com.medusa.gruul.shops.controller.remote.RemoteController: '
          'boolean updateSpecialArea(java.lang.String,java.lang.String)>')
SINK = ('<com.medusa.gruul.shops.service.impl.ShopsRenovationTemPageAssServiceImpl: '
        'boolean updateSpecialArea(java.lang.String,java.lang.String)>')
CHANNEL = {'kind': 'HTTP', 'from': 'http:goods:RemoteShopsService#updateSpecialArea',
           'to': 'ep:shops:GET /feign/updateSpecialArea'}
CLIENT_MODEL = {'id': CHANNEL['from'], 'service': 'goods', 'targetService': 'shops',
                'declaration': {'className': CLIENT, 'method': 'updateSpecialArea'},
                'callSites': [{'className': CALLER_CLS, 'method': 'updateSaleMode'}]}
ENDPOINT = {'id': CHANNEL['to'], 'service': 'shops',
            'handler': {'className': 'com.medusa.gruul.shops.controller.remote.RemoteController',
                        'method': 'updateSpecialArea'}}
SHOPS_SINK_FLOW = f'TaintFlow{{{REMOTE}/1 -> {SINK}[0@L178] invokestatic taint.SourceSink.sink(newLinkName)/0}}'
SRC = f'{SOURCE}/0/modeName'  # the source point: DTO parameter 0, field modeName
FLOW = (SRC, SINK)  # the composed key: source point and sink method, like run.py compares them


def compose(tmp_path, clients=(), channels=(), endpoints=(), listeners=(), publishers=(), runs=()):
    tmp_path.mkdir(parents=True, exist_ok=True)
    m = tmp_path / 'model.json'
    m.write_text(json.dumps({'endpoints': list(endpoints), 'listeners': list(listeners),
                             'publishers': list(publishers), 'httpClients': list(clients),
                             'channels': list(channels)}), encoding='utf-8')
    d = tmp_path / 'runs'
    for svc, lines in runs:
        (d / svc).mkdir(parents=True, exist_ok=True)
        (d / svc / 'run.log').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    whole = tmp_path / 'whole.log'
    whole.write_text('', encoding='utf-8')
    verified = tmp_path / 'verified'
    verified.mkdir(exist_ok=True)
    mc.main(str(m), str(d), str(whole), str(verified))
    return {tuple(k) for k in json.load(open(d / 'composed.json', encoding='utf-8'))['composed']}


def test_feign_call_with_signature(tmp_path):
    """the goods -> shops flow of the current ChannelArgSink: full invoked signature, empty path"""
    flow = f'ChannelFlow{{{SRC} -> {CALLER}[48@L150] <{CLIENT}: boolean updateSpecialArea(java.lang.String,java.lang.String)>/1/}}'
    out = compose(tmp_path, clients=[CLIENT_MODEL], channels=[CHANNEL], endpoints=[ENDPOINT],
                  runs=[('goods', [flow]), ('shops', [SHOPS_SINK_FLOW])])
    assert FLOW in out


def test_feign_call_without_signature(tmp_path):
    """the same flow in logs of the old ChannelArgSink, which prints the bare invoke"""
    flow = f'ChannelFlow{{{SRC} -> {CALLER}[48@L150] invokeinterface $r20.updateSpecialArea($r21, $r22)/1/}}'
    out = compose(tmp_path, clients=[CLIENT_MODEL], channels=[CHANNEL], endpoints=[ENDPOINT],
                  runs=[('goods', [flow]), ('shops', [SHOPS_SINK_FLOW])])
    assert FLOW in out


def test_library_crossing_is_not_composed(tmp_path):
    """whole-system artefacts 31/44: the services share the QueryParam parent class, but no channel
    carries the value - the goods run has no channel flow, so nothing may be composed"""
    out = compose(tmp_path, runs=[('goods', []), ('shops', [SHOPS_SINK_FLOW])])
    assert FLOW not in out


def test_wrong_client_is_not_composed(tmp_path):
    """a channel whose call site is elsewhere must not carry the flow (exact class match)"""
    other = dict(CLIENT_MODEL, id='http:goods:RemoteOtherService#updateSpecialArea',
                 declaration={'className': 'com.medusa.gruul.other.api.feign.RemoteOtherService',
                              'method': 'updateSpecialArea'})
    flow = f'ChannelFlow{{{SRC} -> {CALLER}[48@L150] <{CLIENT}: boolean updateSpecialArea(java.lang.String,java.lang.String)>/1/}}'
    out = compose(tmp_path, clients=[other], endpoints=[ENDPOINT],
                  runs=[('goods', [flow]), ('shops', [SHOPS_SINK_FLOW])])
    assert FLOW not in out


def test_http_argument_index_must_match(tmp_path):
    """the taint enters the receiving endpoint at the same parameter index"""
    flow = f'ChannelFlow{{{SRC} -> {CALLER}[48@L150] <{CLIENT}: boolean updateSpecialArea(java.lang.String,java.lang.String)>/0/}}'
    out = compose(tmp_path, clients=[CLIENT_MODEL], channels=[CHANNEL], endpoints=[ENDPOINT],
                  runs=[('goods', [flow]), ('shops', [SHOPS_SINK_FLOW])])
    assert FLOW not in out


def test_field_path_needs_the_same_field_name(tmp_path):
    """a DTO argument's tainted field continues only into the receiving field of the same name"""
    dto_remote = ('<com.medusa.gruul.shops.controller.remote.RemoteController: '
                  'boolean updateSpecialArea(com.medusa.gruul.shops.model.param.AreaDto)>')
    flow = f'ChannelFlow{{{SRC} -> {CALLER}[48@L150] <{CLIENT}: boolean updateSpecialArea(com.medusa.gruul.shops.model.param.AreaDto)>/0/newLinkName}}'
    sink_flow = f'TaintFlow{{{dto_remote}/0/newLinkName -> {SINK}[2@L41] invokestatic taint.SourceSink.sink($r1)/0}}'
    out = compose(tmp_path, clients=[CLIENT_MODEL], channels=[CHANNEL], endpoints=[ENDPOINT],
                  runs=[('goods', [flow]), ('shops', [sink_flow])])
    assert FLOW in out
    other = sink_flow.replace('/0/newLinkName ->', '/0/link ->')
    out = compose(tmp_path / 'n', clients=[CLIENT_MODEL], channels=[CHANNEL], endpoints=[ENDPOINT],
                  runs=[('goods', [flow]), ('shops', [other])])
    assert FLOW not in out


def test_nested_field_path_is_coarse(tmp_path):
    """a nested field path names no single field: every input of the argument receives the taint"""
    dto_remote = ('<com.medusa.gruul.shops.controller.remote.RemoteController: '
                  'boolean updateSpecialArea(com.medusa.gruul.shops.model.param.AreaDto)>')
    flow = f'ChannelFlow{{{SRC} -> {CALLER}[48@L150] <{CLIENT}: boolean updateSpecialArea(com.medusa.gruul.shops.model.param.AreaDto)>/0/body.newLinkName}}'
    sink_flow = f'TaintFlow{{{dto_remote}/0/link -> {SINK}[2@L41] invokestatic taint.SourceSink.sink($r1)/0}}'
    out = compose(tmp_path, clients=[CLIENT_MODEL], channels=[CHANNEL], endpoints=[ENDPOINT],
                  runs=[('goods', [flow]), ('shops', [sink_flow])])
    assert FLOW in out


def test_amqp_field_level(tmp_path):
    """a send carrying payload field reason continues into the listener input of that field"""
    controller = ('<com.medusa.gruul.goods.web.controller.manager.GoodsOrderController: '
                  'com.medusa.gruul.common.core.util.Result create('
                  'com.medusa.gruul.goods.api.model.OrderDto)>')
    sender = ('<com.medusa.gruul.goods.mq.Sender: void sendOrderEvent('
              'com.medusa.gruul.goods.api.model.OrderEventDto)>')
    sender_cls = 'com.medusa.gruul.goods.mq.Sender'
    flow = (f'ChannelFlow{{{controller}/0/reason -> {sender}[10@L40] '
            '<org.springframework.amqp.rabbit.core.RabbitTemplate: void convertAndSend('
            'java.lang.String,java.lang.String,java.lang.Object)>/2/reason}}')
    listener = {'id': 'lis:shops:OrderEventsListener#onOrderEvent', 'service': 'shops',
                'handler': {'className': 'com.medusa.gruul.shops.mq.OrderEventsListener',
                            'method': 'onOrderEvent'}}
    publisher = {'id': 'amqp:goods:Sender#sendOrderEvent', 'service': 'goods',
                 'sendSite': {'className': sender_cls, 'method': 'sendOrderEvent'}}
    channel = {'kind': 'AMQP', 'from': publisher['id'], 'to': listener['id']}
    sink_flow = ('TaintFlow{<com.medusa.gruul.shops.mq.OrderEventsListener: void onOrderEvent('
                 'com.medusa.gruul.common.event.OrderEvent)>/0/reason -> '
                 '<com.medusa.gruul.shops.service.impl.OrderServiceImpl: void persist('
                 'java.lang.String)>[3@L60] invokestatic taint.SourceSink.sink($r1)/0}')
    out = compose(tmp_path, listeners=[listener], publishers=[publisher], channels=[channel],
                  runs=[('goods', [flow]), ('shops', [sink_flow])])
    assert (f'{controller}/0/reason',
            '<com.medusa.gruul.shops.service.impl.OrderServiceImpl: void persist(java.lang.String)>') in out


def test_listener_is_never_a_chain_root(tmp_path):
    """a listener input is tainted only through a chain: its own finding is not a root"""
    listener_flow = ('TaintFlow{<com.medusa.gruul.shops.mq.OrderEventsListener: void onOrderEvent('
                     'com.medusa.gruul.common.event.OrderEvent)>/0/reason -> '
                     '<com.medusa.gruul.shops.service.impl.OrderServiceImpl: void persist('
                     'java.lang.String)>[3@L60] invokestatic taint.SourceSink.sink($r1)/0}')
    listener = {'id': 'lis:shops:OrderEventsListener#onOrderEvent', 'service': 'shops',
                'handler': {'className': 'com.medusa.gruul.shops.mq.OrderEventsListener',
                            'method': 'onOrderEvent'}}
    out = compose(tmp_path, listeners=[listener], runs=[('shops', [listener_flow])])
    assert (('<com.medusa.gruul.shops.mq.OrderEventsListener: void onOrderEvent('
             'com.medusa.gruul.common.event.OrderEvent)>/0/reason',
             '<com.medusa.gruul.shops.service.impl.OrderServiceImpl: void persist(java.lang.String)>')
            not in out)
