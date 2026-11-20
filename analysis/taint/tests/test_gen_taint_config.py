"""Unit test of gen_taint_config.py on a tiny compiled fixture (needs a JDK: JAVA_HOME).
run: python -I -m unittest discover -s analysis/taint/tests -v"""
import io
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'scripts'))
import gen_taint_config as gen  # noqa: E402
import vkr_config  # noqa: E402
import vkr_yaml  # noqa: E402

JDK = os.environ.get('JAVA_HOME')

SERVICE_SOURCES = {
    'jakarta/persistence/Entity.java': 'package jakarta.persistence; public @interface Entity {}',
    'org/springframework/data/domain/Pageable.java': 'package org.springframework.data.domain; public interface Pageable {}',
    'shop/a/App.java': 'package shop.a; public class App {}',
    'shop/a/dto/CreateRequest.java': 'package shop.a.dto; public record CreateRequest(String name, Long qty) {}',
    'shop/a/model/Status.java': 'package shop.a.model; public enum Status { NEW }',
    'shop/a/web/OrderController.java': """
        package shop.a.web;
        import shop.a.dto.CreateRequest;
        import shop.a.model.Status;
        import org.springframework.data.domain.Pageable;
        public class OrderController {
            public Object create(String key, CreateRequest body) { return null; }
            public Object search(Status status, String q, int page, Pageable p) { return null; }
            public Object get(Long id) { return null; }
            public Object get(Long id, String view) { return null; }
            public Object internal(String x) { return null; }
        }""",
    'shop/a/client/StockClient.java': """
        package shop.a.client;
        public interface StockClient { Object reserve(java.util.List ids, String note); }""",
    'shop/a/outbox/OutboxService.java': """
        package shop.a.outbox;
        public class OutboxService {
            public void enqueue(String exchange, String key, shop.common.event.DomainEvent e, Object id) {}
        }""",
    'shop/a/service/Lifecycle.java': """
        package shop.a.service;
        import shop.common.event.*;
        public class Lifecycle {
            shop.a.outbox.OutboxService outbox;
            public void place(String id) { enqueue("k", new OrderPlaced(id, "n", 1L)); }
            private void enqueue(String key, DomainEvent e) { outbox.enqueue("x", key, e, null); }
        }""",
    'shop/a/messaging/Consumer.java': """
        package shop.a.messaging;
        public class Consumer { public void onPaid(String header, shop.common.event.Paid event) {} }""",
    'shop/a/entity/Order.java': """
        package shop.a.entity;
        @jakarta.persistence.Entity public class Order { private String note; }""",
    'shop/a/mapper/OrderMapper.java': """
        package shop.a.mapper;
        public interface OrderMapper { java.util.List find(String orderBy); Object byId(Long id); }""",
}
SHARED_SOURCES = {
    'shop/common/event/DomainEvent.java': 'package shop.common.event; public interface DomainEvent {}',
    'shop/common/event/OrderPlaced.java':
        'package shop.common.event; public record OrderPlaced(String id, String name, Long qty) implements DomainEvent {}',
    'shop/common/event/Paid.java': 'package shop.common.event; public record Paid(String id) implements DomainEvent {}',
    # a MyBatis Generator style mapper in the shared module: the Example carries orderByClause
    'shop/common/model/ItemExample.java': """
        package shop.common.model;
        public class ItemExample extends BaseExample { java.util.List criteria; }""",
    'shop/common/model/BaseExample.java': """
        package shop.common.model;
        public class BaseExample { protected String orderByClause;
            public void setOrderByClause(String s) { orderByClause = s; } }""",
    'shop/common/mapper/ItemMapper.java': """
        package shop.common.mapper;
        import org.apache.ibatis.annotations.Param;
        public interface ItemMapper {
            java.util.List selectByExample(shop.common.model.ItemExample example);
            java.util.List sorted(@Param("col") String col, @Param("limit") int limit);
            java.util.List positional(String table, String col);
        }""",
}
# a library on the compile classpath only (not an application or shared package)
LIB_SOURCES = {
    'org/apache/ibatis/annotations/Param.java': """
        package org.apache.ibatis.annotations;
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
        public @interface Param { String value(); }""",
}
ITEM_MAPPER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="shop.common.mapper.ItemMapper">
  <sql id="Example_Where_Clause">
    <where><foreach collection="criteria" item="criterion">and ${criterion.condition} #{criterion.value}</foreach></where>
  </sql>
  <select id="selectByExample" resultType="map">
    select * from item <if test="_parameter != null"><include refid="Example_Where_Clause" /></if>
    <if test="orderByClause != null">order by ${orderByClause}</if>
  </select>
  <select id="sorted" resultType="map">select * from item order by ${col} limit ${limit}</select>
  <select id="positional" resultType="map">
    <bind name="t" value="'item'" />select ${param2} from ${t}
  </select>
</mapper>
"""
MAPPER_XML = """<?xml version="1.0"?>
<mapper namespace="shop.a.mapper.OrderMapper">
  <select id="find" resultType="map">select * from orders order by ${orderBy}</select>
  <select id="byId" resultType="map">select * from orders where id = #{id}</select>
</mapper>
"""


def ref(cls, method, line=1):
    return {'file': 'x.java', 'className': cls, 'method': method, 'line': line}


MODEL = {
    'endpoints': [
        {'id': 'ep:svc-a:POST /orders', 'service': 'svc-a', 'exposedViaGateway': True,
         'params': [{'name': 'key', 'type': 'String', 'source': 'HEADER'},
                    {'name': 'body', 'type': 'CreateRequest', 'source': 'BODY'}],
         'handler': ref('shop.a.web.OrderController', 'create')},
        {'id': 'ep:svc-a:GET /orders', 'service': 'svc-a', 'exposedViaGateway': True,
         'params': [{'name': 'status', 'type': 'Status', 'source': 'QUERY'},
                    {'name': 'q', 'type': 'String', 'source': 'QUERY'},
                    {'name': 'page', 'type': 'int', 'source': 'QUERY'},
                    {'name': 'p', 'type': 'Pageable', 'source': 'QUERY'}],
         'handler': ref('shop.a.web.OrderController', 'search', 2)},
        {'id': 'ep:svc-a:GET /orders/{id}', 'service': 'svc-a', 'exposedViaGateway': True,
         'params': [{'name': 'id', 'type': 'Long', 'source': 'PATH'}],
         'handler': ref('shop.a.web.OrderController', 'get', 3)},
        {'id': 'ep:svc-a:GET /internal', 'service': 'svc-a', 'exposedViaGateway': False,
         'params': [{'name': 'x', 'type': 'String', 'source': 'QUERY'}],
         'handler': ref('shop.a.web.OrderController', 'internal', 4)},
    ],
    'httpClients': [{'id': 'http:svc-a:StockClient#reserve', 'service': 'svc-a',
                     'params': [{'name': 'ids', 'type': 'List', 'source': 'BODY'},
                                {'name': 'note', 'type': 'String', 'source': 'QUERY'}],
                     'declaration': ref('shop.a.client.StockClient', 'reserve'), 'callSites': []},
                    # a RestTemplate call: the declaration is the call statement, no client interface
                    {'id': 'http:svc-a:Lifecycle#place:9:GET svc-b/stock/{?}', 'service': 'svc-a',
                     'params': [], 'clientKind': 'REST_TEMPLATE',
                     'declaration': ref('shop.a.service.Lifecycle', 'place', 9),
                     'callSites': [ref('shop.a.service.Lifecycle', 'place', 9)]}],
    'publishers': [{'id': 'pub:svc-a:Lifecycle#place', 'service': 'svc-a', 'mechanism': 'OUTBOX',
                    'exchange': 'x', 'routingKey': 'k', 'payloadType': 'OrderPlaced',
                    'origin': ref('shop.a.service.Lifecycle', 'place'),
                    'sendSite': ref('shop.a.service.Lifecycle', 'enqueue')}],
    'listeners': [{'id': 'lis:svc-a:Consumer#onPaid', 'service': 'svc-a', 'queue': 'q', 'dispatch': 'METHOD',
                   'payloadType': 'Paid', 'payloadParam': 'event', 'handler': ref('shop.a.messaging.Consumer', 'onPaid')}],
    'channels': [], 'unresolved': [], 'bindings': [],
}


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(textwrap.dedent(text))


@unittest.skipUnless(JDK and os.path.isfile(os.path.join(JDK, 'bin', 'javac' + ('.exe' if os.name == 'nt' else ''))),
                     'JAVA_HOME (a JDK) is needed to compile the fixture')
class GenerateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = cls.root = cls.tmp.name
        write(os.path.join(root, 'pom.xml'), '<project><artifactId>p</artifactId></project>')
        for mod, sources in (('svc-a', SERVICE_SOURCES), ('common-lib', SHARED_SOURCES)):
            write(os.path.join(root, mod, 'pom.xml'), f'<project><artifactId>{mod}</artifactId></project>')
            for rel, text in sources.items():
                write(os.path.join(root, mod, 'src', 'main', 'java', rel), text)
        write(os.path.join(root, 'svc-a', 'src', 'main', 'java', 'shop', 'a', 'Boot.java'),
              '// @SpringBootApplication\npackage shop.a; class Boot {}')
        javac = os.path.join(JDK, 'bin', 'javac')
        common_out = os.path.join(root, 'common-lib', 'target', 'classes')
        lib_out = os.path.join(root, 'lib-classes')
        for rel, text in LIB_SOURCES.items():
            write(os.path.join(root, 'lib-src', 'src', rel), text)
        cls.compile(javac, os.path.join(root, 'lib-src'), lib_out, [])
        cls.compile(javac, os.path.join(root, 'common-lib'), common_out, [lib_out])
        cls.compile(javac, os.path.join(root, 'svc-a'), os.path.join(root, 'svc-a', 'target', 'classes'), [common_out])
        write(os.path.join(root, 'svc-a', 'target', 'classes', 'mapper', 'OrderMapper.xml'), MAPPER_XML)
        write(os.path.join(common_out, 'shop', 'common', 'mapper', 'ItemMapper.xml'), ITEM_MAPPER_XML)
        with zipfile.ZipFile(os.path.join(root, 'svc-a', 'target', 'svc-a.jar'), 'w') as z:
            z.writestr('BOOT-INF/lib/spring-amqp-3.2.7.jar', b'')
            z.writestr('BOOT-INF/lib/common-lib-1.0.jar', b'')
            # catalogue sinks are kept only for classes the service packages: slf4j and spring-jdbc
            # are, JPA is not
            for lib, entry in (('slf4j-api-2.0.jar', 'org/slf4j/Logger.class'),
                             ('spring-jdbc-6.1.jar', 'org/springframework/jdbc/core/JdbcTemplate.class'),
                             ('spring-web-6.1.jar', 'org/springframework/web/client/RestTemplate.class')):
                buf = io.BytesIO()
                with zipfile.ZipFile(buf, 'w') as inner:
                    inner.writestr(entry, b'')
                z.writestr('BOOT-INF/lib/' + lib, buf.getvalue())
        write(os.path.join(root, 'vkr-taint.yml'), """\
            version: 1
            project: { root: . }
            toolchain: { java-home: "${JAVA_HOME}" }
            """)
        cfg = vkr_config.load(os.path.join(root, 'vkr-taint.yml'), require=('java', 'artifacts'))
        cls.cfg = cfg
        cls.text, cls.disc, cls.notes = gen.generate(cfg, MODEL, cfg['services'][0], JDK)
        cls.conf = vkr_yaml.loads(cls.text)

    @staticmethod
    def compile(javac, module, out, cp):
        files = [os.path.join(d, f) for d, _, fs in os.walk(os.path.join(module, 'src')) for f in fs
                 if f.endswith('.java')]
        args = [javac, '--release', '17', '-d', out] + (['-cp', os.pathsep.join(cp)] if cp else []) + files
        subprocess.run(args, check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def sigs(self, section):
        return [(e.get('method'), e.get('index')) for e in self.conf[section]]

    def test_service_discovered(self):
        self.assertEqual([m['name'] for m in self.cfg['services']], ['svc-a'])
        self.assertEqual([m['name'] for m in self.cfg['shared']], ['common-lib'])

    def test_endpoint_sources(self):
        create = '<shop.a.web.OrderController: java.lang.Object create(java.lang.String,shop.a.dto.CreateRequest)>'
        search = ('<shop.a.web.OrderController: java.lang.Object search(shop.a.model.Status,java.lang.String,int,'
                  'org.springframework.data.domain.Pageable)>')
        src = self.sigs('sources')
        self.assertIn((create, 0), src)
        self.assertIn((create, 1), src)
        self.assertEqual([i for m, i in src if m == search], [1])  # not the enum, int or Pageable
        self.assertIn(('<shop.a.web.OrderController: java.lang.Object get(java.lang.Long)>', 0), src)  # overload
        self.assertFalse(any('internal' in m for m, _ in src))  # not exposed through the gateway
        self.assertEqual(self.notes, [])

    def test_listener_source_marked(self):
        self.assertIn('<shop.a.messaging.Consumer: void onPaid(java.lang.String,shop.common.event.Paid)>", '
                      'index: 1 }  # AMQP listener', self.text)

    def test_channel_sinks(self):
        sinks = self.sigs('sinks')
        reserve = '<shop.a.client.StockClient: java.lang.Object reserve(java.util.List,java.lang.String)>'
        self.assertIn((reserve, 0), sinks)
        self.assertIn((reserve, 1), sinks)
        # the publisher goes through a helper taking the payload: sink on the helper's parameter
        self.assertIn(('<shop.a.service.Lifecycle: void enqueue(java.lang.String,shop.common.event.DomainEvent)>', 1),
                      sinks)
        self.assertFalse(any('OutboxService' in m for m, _ in sinks))
        self.assertIn(('<org.springframework.amqp.core.AmqpTemplate^: void convertAndSend('
                       'java.lang.String,java.lang.String,java.lang.Object)>', 2), sinks)
        self.assertEqual(self.disc['outboxSinks'], [['shop.a.service.Lifecycle', 'enqueue']])

    def test_catalogue_and_mybatis_sinks(self):
        sinks = self.sigs('sinks')
        self.assertIn(('<org.slf4j.Logger: void info(java.lang.String,java.lang.Object[])>', '1[*]'), sinks)
        self.assertIn(('<org.springframework.jdbc.core.JdbcTemplate: void execute(java.lang.String)>', 0), sinks)
        self.assertIn(('<shop.a.mapper.OrderMapper: java.util.List find(java.lang.String)>', 0), sinks)
        self.assertFalse(any('byId' in m for m, _ in sinks))  # #{} is a bind parameter
        self.assertFalse(any('persistence.EntityManager' in m for m, _ in sinks))  # JPA not packaged

    def test_mybatis_sink_names_the_spliced_parameter(self):
        sinks = self.sigs('sinks')
        item = [(m, i) for m, i in sinks if 'ItemMapper' in m]
        self.assertEqual(sorted(item, key=str), sorted([
            # one parameter: ${orderByClause} is its (inherited) field; the foreach item
            # ${criterion.condition} of the included where clause is not a parameter
            ('<shop.common.mapper.ItemMapper: java.util.List selectByExample(shop.common.model.ItemExample)>',
             '0.orderByClause'),
            # @Param names; the int spliced by ${limit} is no injection
            ('<shop.common.mapper.ItemMapper: java.util.List sorted(java.lang.String,int)>', 0),
            # param<N> is 1-based; the bind variable ${t} is not a parameter
            ('<shop.common.mapper.ItemMapper: java.util.List positional(java.lang.String,java.lang.String)>', 1),
        ], key=str))
        self.assertIn(['shop.common.mapper.ItemMapper', 'selectByExample'], self.disc['mybatisSinks'])
        self.assertIn(['shop.a.mapper.OrderMapper', 'find'], self.disc['mybatisSinks'])
        self.assertFalse([n for n in self.notes if 'MyBatis' in n])

    def test_rest_template_channel_end(self):
        sinks = self.sigs('sinks')
        exchange = ('<org.springframework.web.client.RestTemplate: org.springframework.http.ResponseEntity '
                    'exchange(java.lang.String,org.springframework.http.HttpMethod,org.springframework.http.HttpEntity,'
                    'java.lang.Class,java.lang.Object[])>')
        self.assertIn((exchange, 0), sinks)  # the URL with the path variables in it
        self.assertIn((exchange, 2), sinks)  # the request entity
        self.assertFalse(any(' place(' in m for m, _ in sinks))  # the calling method is not a Feign method
        self.assertFalse(any('Lifecycle#place' in n for n in self.notes))
        tr = [e['method'] for e in self.conf['transfers']]
        self.assertIn('<org.springframework.http.HttpEntity: void <init>(java.lang.Object)>', tr)

    def test_transfers_and_discovery(self):
        tr = [e['method'] for e in self.conf['transfers']]
        self.assertIn('<shop.a.dto.*: * *()>', tr)
        self.assertIn('<shop.common.event.*: * *()>', tr)
        ctors = [m for m in tr if '<init>' in m and m.startswith('<shop.')]
        self.assertEqual(ctors, [f'<shop.common.event.*: void <init>(*{{{n}+}})>' for n in (1, 2, 3)])
        self.assertEqual(self.disc['dtoPackages'], ['shop.a.dto'])
        self.assertEqual(self.disc['eventPackages'], ['shop.common.event'])
        self.assertEqual(self.disc['entityClasses'], ['shop.a.entity.Order'])
        self.assertIn('shop.common', self.disc['appPackages'])  # common prefix of the shared module

    def test_cli_writes_files(self):
        out = os.path.join(self.root, 'gen')
        model = os.path.join(self.root, 'model.json')
        with open(model, 'w', encoding='utf-8') as f:
            json.dump(MODEL, f)
        r = subprocess.run([sys.executable, '-I', gen.__file__, '--config', os.path.join(self.root, 'vkr-taint.yml'),
                            '--model', model, '--out-dir', out], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(os.path.isfile(os.path.join(out, 'taint-svc-a.yml')))
        self.assertTrue(os.path.isfile(os.path.join(out, 'discovery-svc-a.json')))


if __name__ == '__main__':
    unittest.main()
