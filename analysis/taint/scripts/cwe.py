"""The built-in sink catalogues, one per CWE, and everything the scripts need to know about them:
the name a configuration lists under taint.sinks, how summarize.py recognises a sink call of that
CWE in the Micans log, and how a finding is presented in SARIF.

  vkr_config.py   the catalogue names a vkr-taint.yml may list
  vkr_taint.py    the configuration generated for a repository without one: every catalogue
  summarize.py    the CWE of a sink call (of_call, of_constructor)
  sarif.py        the rules: name, description, level, security-severity

The sink methods themselves are Tai-e config entries, kept in gen_taint_config.CATALOGUES under the
same names; tests/test_cwe.py checks that the two tables agree.
"""
import re
from typing import NamedTuple


class Cwe(NamedTuple):
    id: str            # 'CWE-89'; the catalogue is its lower case, 'cwe-89'
    rule: str          # SARIF rule name
    title: str         # SARIF short description
    description: str   # SARIF full description
    level: str         # SARIF level
    severity: str      # security-severity, by which GitHub code scanning orders alerts
    # The Micans log prints a sink call without the callee's class ($r6.executeQuery($r5)), so a
    # call is told apart by the method name; a constructor call prints no class at all, so it is
    # told apart by the classes the calling method constructs (internal names).
    calls: str
    constructors: tuple = ()

    @property
    def catalogue(self):
        return self.id.lower()


# In the order summarize.py tries the method names: the first match wins (a logger's error() is a log
# sink, not an SQL statement; get/of last, they are the most common names).
CWES = (
    Cwe('CWE-117', 'LogInjection', 'Log injection',
        'A request parameter reaches a log statement unneutralized, possibly through other services.',
        'warning', '5.3', r'trace|debug|info|warn|error'),
    Cwe('CWE-89', 'SqlInjection', 'SQL injection',
        'A request parameter reaches an SQL query (JPA, JdbcTemplate or a MyBatis ${} mapper), '
        'possibly through other services.',
        'error', '8.8',
        r'createNativeQuery|createQuery|query\w*|update|execute\w*|prepareStatement|prepareCall|addBatch'
        r'|batchUpdate'),
    Cwe('CWE-918', 'ServerSideRequestForgery', 'Server-side request forgery',
        'A request parameter decides the URL the service opens, possibly through other services.',
        'error', '8.6', r'openConnection|openStream|newBuilder|uri'),
    Cwe('CWE-1336', 'TemplateInjection', 'Template injection',
        'A request parameter is compiled as a template (FreeMarker, Thymeleaf, Velocity), possibly '
        'through other services.',
        'error', '9.8', r'process|evaluate', ('freemarker/template/Template',)),
    Cwe('CWE-79', 'CrossSiteScripting', 'Cross-site scripting',
        'A request parameter, or a value stored from one, is returned as the response body without '
        'HTML encoding, possibly through other services.',
        'error', '6.1', r'print|println|write|format|printf|append'),
    Cwe('CWE-78', 'CommandInjection', 'OS command injection',
        'A request parameter reaches an operating system command (Runtime.exec or ProcessBuilder: the '
        'command line, its arguments or its environment), possibly through other services.',
        'error', '9.8', r'exec|command', ('java/lang/ProcessBuilder',)),
    Cwe('CWE-22', 'PathTraversal', 'Path traversal',
        'A request parameter names the file the service opens (the java.io file constructors, '
        'Paths.get), possibly through other services.',
        'error', '7.5', r'get|of',
        tuple(f'java/io/{c}' for c in ('File', 'FileInputStream', 'FileOutputStream', 'FileReader', 'FileWriter',
                                       'RandomAccessFile'))),
)
BY_ID = {c.id: c for c in CWES}
# every built-in catalogue, the names taint.sinks accepts
CATALOGUES = tuple(c.catalogue for c in CWES)
_CALLS = [(c.id, re.compile(rf'\.({c.calls})\(')) for c in CWES]


def of_call(call, sql=False):
    """The CWE of a sink call, by the name of the called method; None for a call of no catalogue (a
    sink of a user's Tai-e file). sql: the callee is known to run SQL (a MyBatis mapper method whose
    statement splices a parameter with ${}), whatever its name."""
    for cwe, pattern in _CALLS:
        if pattern.search(call) or (sql and cwe == 'CWE-89'):
            return cwe
    return None


def of_constructor(constructed):
    """The CWE of a constructor call in a method that constructs these classes (internal names), or None."""
    for c in CWES:
        if any(owner in constructed for owner in c.constructors):
            return c.id
    return None
