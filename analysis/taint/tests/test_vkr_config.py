"""Unit tests of vkr_yaml.py, vkr_config.py and the text helpers of vkr_prepare.py.
run: python -I -m unittest discover -s analysis/taint/tests -v"""
import os
import sys
import tempfile
import textwrap
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'scripts'))
import vkr_config  # noqa: E402
import vkr_prepare  # noqa: E402
import vkr_yaml  # noqa: E402


def write(path, text=''):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(textwrap.dedent(text))


def make_repo(root):
    """svc-a (service), gateway (Spring Cloud Gateway), config-server, common-lib (shared)."""
    write(os.path.join(root, 'pom.xml'), '<project><artifactId>parent</artifactId></project>')
    write(os.path.join(root, 'svc-a', 'pom.xml'),
          '<project><parent><artifactId>parent</artifactId></parent><artifactId>svc-a</artifactId></project>')
    write(os.path.join(root, 'svc-a', 'src', 'main', 'java', 'a', 'App.java'),
          '@SpringBootApplication public class App {}')
    write(os.path.join(root, 'gateway', 'pom.xml'),
          '<project><artifactId>gateway</artifactId><dependencies><dependency>'
          '<artifactId>spring-cloud-starter-gateway</artifactId></dependency></dependencies></project>')
    write(os.path.join(root, 'gateway', 'src', 'main', 'java', 'g', 'Gw.java'), '@SpringBootApplication class Gw {}')
    write(os.path.join(root, 'config-server', 'pom.xml'), '<project><artifactId>config-server</artifactId></project>')
    write(os.path.join(root, 'config-server', 'src', 'main', 'java', 'c', 'C.java'),
          '@SpringBootApplication @EnableConfigServer class C {}')
    write(os.path.join(root, 'common-lib', 'pom.xml'),
          '<project><parent><artifactId>parent</artifactId></parent><artifactId>common-lib</artifactId></project>')
    write(os.path.join(root, 'common-lib', 'src', 'main', 'java', 'x', 'U.java'), 'class U {}')


def build(root):
    os.makedirs(os.path.join(root, 'svc-a', 'target', 'classes', 'a'), exist_ok=True)
    os.makedirs(os.path.join(root, 'common-lib', 'target', 'classes', 'x'), exist_ok=True)
    with zipfile.ZipFile(os.path.join(root, 'svc-a', 'target', 'svc-a.jar'), 'w') as z:
        z.writestr('BOOT-INF/lib/common-lib-1.0.jar', b'')


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        make_repo(self.root)
        self.jdk = os.path.join(self.root, 'jdk')
        write(os.path.join(self.jdk, 'bin', 'javap' + ('.exe' if os.name == 'nt' else '')), '')
        self.micans = os.path.join(self.root, 'micans.jar')
        write(self.micans, '')

    def tearDown(self):
        self.tmp.cleanup()

    def config(self, text):
        p = os.path.join(self.root, 'vkr-taint.yml')
        write(p, text)
        return p


MINIMAL = """\
    version: 1
    project:
      root: .
    services: auto
    shared: auto
    toolchain:
      java-home: ${JAVA_HOME}
      micans-jar: ${VKR_MICANS_JAR:-${MICANS_JAR}}
    """
MINIMAL = textwrap.dedent(MINIMAL)


class YamlTest(unittest.TestCase):
    def test_subset(self):
        v = vkr_yaml.loads(textwrap.dedent("""\
            a: 1            # comment
            b: [x, "y z", 3]
            c:
              - { method: "<A: void m(java.lang.String)>", index: "1[*]" }  # AMQP listener
              - name: n
                module: m
            d: >-
              folded
              text
            e: 'it''s'
            """))
        self.assertEqual(v['a'], 1)
        self.assertEqual(v['b'], ['x', 'y z', 3])
        self.assertEqual(v['c'][0], {'method': '<A: void m(java.lang.String)>', 'index': '1[*]'})
        self.assertEqual(v['c'][1], {'name': 'n', 'module': 'm'})
        self.assertEqual(v['d'], 'folded text')
        self.assertEqual(v['e'], "it's")

    def test_dump_entry_roundtrip(self):
        e = {'method': '<java.util.Map$Entry^: java.lang.Object getKey()>', 'from': 'base', 'to': 'result',
             'index': '1[*]', 'n': 0}
        self.assertEqual(vkr_yaml._flow(vkr_yaml.dump_entry(e)), e)


class SubstitutionTest(unittest.TestCase):
    def test_env_and_defaults(self):
        missing = []
        env = {'A': 'x', 'EMPTY': ''}
        self.assertEqual(vkr_config.substitute('${A}/b', env, missing), 'x/b')
        self.assertEqual(vkr_config.substitute('${B:-dflt}', env, missing), 'dflt')
        self.assertEqual(vkr_config.substitute('${EMPTY:-d}', env, missing), 'd')
        self.assertEqual(vkr_config.substitute('${B:-${A}}', env, missing), 'x')
        self.assertEqual(missing, [])
        vkr_config.substitute('${B:-${C}}', env, missing)
        self.assertEqual(missing, ['B', 'C'])

    def test_bad_name(self):
        with self.assertRaises(vkr_config.ConfigError):
            vkr_config.substitute('${1X}', {}, [])


class LoadTest(Base):
    def env(self, **kw):
        return {'JAVA_HOME': self.jdk, 'VKR_MICANS_JAR': self.micans, **kw}

    def test_auto_discovery(self):
        cfg = vkr_config.load(self.config(MINIMAL), env=self.env())
        self.assertEqual([m['name'] for m in cfg['services']], ['svc-a'])
        self.assertEqual([(m['name'], m['artifact']) for m in cfg['shared']], [('common-lib', 'common-lib')])
        self.assertIn('config-server', cfg['notes'][0])
        self.assertIn('gateway', cfg['notes'][0])
        self.assertEqual(cfg['analysis'], {'context': '1-call', 'heap': '24g', 'world_cache': 'off'})

    def test_missing_toolchain_names_the_variable(self):
        p = self.config(MINIMAL)
        with self.assertRaises(vkr_config.ConfigError) as e:
            vkr_config.load(p, env={'JAVA_HOME': self.jdk}, require=('java', 'micans'))
        msg = str(e.exception)
        self.assertIn('toolchain.micans-jar', msg)
        self.assertIn('VKR_MICANS_JAR', msg)
        self.assertIn('MICANS_JAR', msg)
        self.assertNotIn('toolchain.java-home', msg)
        with self.assertRaises(vkr_config.ConfigError) as e:
            vkr_config.load(p, env={}, require=('java',))
        self.assertIn('toolchain.java-home: environment variable JAVA_HOME is not set', str(e.exception))
        # not required: loads
        vkr_config.load(p, env={})

    def test_toolchain_paths_checked(self):
        p = self.config(MINIMAL)
        with self.assertRaises(vkr_config.ConfigError) as e:
            vkr_config.load(p, env=self.env(JAVA_HOME=os.path.join(self.root, 'nojdk'),
                                            VKR_MICANS_JAR=os.path.join(self.root, 'no.jar')),
                            require=('java', 'micans'))
        self.assertIn('directory not found', str(e.exception))
        self.assertIn('no.jar', str(e.exception))

    def test_artifacts_required(self):
        p = self.config(MINIMAL)
        with self.assertRaises(vkr_config.ConfigError) as e:
            vkr_config.load(p, env=self.env(), require=('artifacts',))
        self.assertIn('services.svc-a: no built classes', str(e.exception))
        build(self.root)
        cfg = vkr_config.load(p, env=self.env(), require=('artifacts',))
        self.assertTrue(cfg['services'][0]['jar'].endswith('svc-a.jar'))

    def test_explicit_services_and_errors(self):
        p = self.config(MINIMAL.replace('services: auto', 'services:\n  - svc-a\n  - nope')
                        + 'taint:\n  sinks: [cwe-89, cwe-999]\n')
        with self.assertRaises(vkr_config.ConfigError) as e:
            vkr_config.load(p, env=self.env())
        self.assertIn('services.nope: module directory not found', str(e.exception))
        self.assertIn('unknown catalogue "cwe-999"', str(e.exception))

    def test_overrides_need_reasons(self):
        write(os.path.join(self.root, 'ov.yml'), """\
            sinks:
              - { method: "<A: void m(java.lang.String)>", index: 0 }
            exclusions:
              - { field: "<A: java.lang.String f>" }
            """)
        p = self.config(MINIMAL + 'taint:\n  overrides: ov.yml\n')
        with self.assertRaises(vkr_config.ConfigError) as e:
            vkr_config.load(p, env=self.env())
        self.assertIn('must carry a reason', str(e.exception))
        write(os.path.join(self.root, 'ov.yml'), """\
            sinks:
              - { method: "<A: void m(java.lang.String)>", index: 0 }
            services:
              svc-a:
                transfers:
                  - { method: "<B: * *()>", from: base, to: result }
            exclusions:
              - field: "<A: java.lang.String f>"
                reason: validated by @Pattern at every writer
            """)
        cfg = vkr_config.load(p, env=self.env())
        add, excl, helpers = vkr_prepare.overrides_for(cfg, 'svc-a')
        self.assertEqual(len(add['sinks']), 1)
        self.assertEqual(len(add['transfers']), 1)
        self.assertEqual(excl[0]['field'], '<A: java.lang.String f>')

    def test_find_config_upwards(self):
        p = self.config(MINIMAL)
        sub = os.path.join(self.root, 'svc-a', 'src')
        self.assertEqual(vkr_config.find_config(sub), p)


class MergeTest(unittest.TestCase):
    TEXT = textwrap.dedent("""\
        call-site-mode: true

        sources:
          - { kind: field, field: "<A: java.lang.String f>" }
          - { kind: field, field: "<A: java.lang.String g>" }

        sinks:
          - { method: "<S: void s(java.lang.String)>", index: 0 }

        transfers:
          - { method: "<T: * *()>", from: base, to: result }
        """)

    def test_merge_skips_duplicates_and_adds_sections(self):
        out = vkr_prepare.merge(self.TEXT, {
            'sinks': [{'method': '<S: void s(java.lang.String)>', 'index': 0},
                      {'method': '<S: void t(java.lang.String)>', 'index': 0}],
            'sanitizers': [{'kind': 'param', 'method': '<H: java.lang.String h(java.lang.String)>', 'index': 0}],
        }, 'overrides')
        v = vkr_yaml.loads(out)
        self.assertEqual(len(v['sinks']), 2)
        self.assertEqual(len(v['transfers']), 1)
        self.assertEqual(len(v['sanitizers']), 1)
        self.assertLess(out.index('void t('), out.index('transfers:'))

    def test_exclude(self):
        out, removed = vkr_prepare.exclude(self.TEXT, [{'field': '<A: java.lang.String f>', 'reason': 'r'}])
        self.assertEqual(len(removed), 1)
        self.assertNotIn('String f>', out)
        self.assertIn('String g>', out)


if __name__ == '__main__':
    unittest.main()
