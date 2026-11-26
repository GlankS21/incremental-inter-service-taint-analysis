"""Builds the servlet test cases of Juliet Java 1.3 (NIST SARD test suite 111) as one war per CWE, the
layout vkr-taint reads: <out>/juliet-<cwe>/{pom.xml, src/main/java, target/classes, target/<name>.war}.

Only the test cases whose source is a servlet request getter (getCookies_Servlet, getParameter_Servlet,
getQueryString_Servlet) are taken: the tool's sources are the request of a web application, so the
other sources of Juliet (environment, files, console, sockets, database, properties) are out of its
threat model. Each war holds testcasesupport and the test cases of one CWE.

usage: build_juliet.py <Juliet Java dir (with src/)> <out dir> --servlet-api <javax.servlet-api jar>
       [--javac <javac>] [--cwes CWE78,CWE89,...]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile

CWES = {
    'cwe78': 'CWE78_OS_Command_Injection',
    'cwe89': 'CWE89_SQL_Injection',
    'cwe23': 'CWE23_Relative_Path_Traversal',
    'cwe36': 'CWE36_Absolute_Path_Traversal',
    'cwe80': 'CWE80_XSS',
    'cwe81': 'CWE81_XSS_Error_Message',
    'cwe83': 'CWE83_XSS_Attribute',
}
SERVLET_SOURCE = re.compile(r'_(getCookies|getParameter|getQueryString)_Servlet_')

POM = """<project><modelVersion>4.0.0</modelVersion><groupId>juliet</groupId><artifactId>{name}</artifactId>
<version>1.3</version><packaging>war</packaging>
<dependencies><dependency><groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId>
<version>3.1.0</version><scope>provided</scope></dependency></dependencies></project>
"""


def cases(src, cwe_dir):
    """The servlet-source test case files of one CWE (the antbuild copies are skipped)."""
    out = []
    for root, dirs, files in os.walk(os.path.join(src, 'testcases', cwe_dir)):
        dirs[:] = sorted(d for d in dirs if d != 'antbuild')
        out += [os.path.join(root, f) for f in sorted(files) if f.endswith('.java') and SERVLET_SOURCE.search(f)]
    return out


def build(src, out, name, files, servlet_api, javac):
    mod = os.path.join(out, name)
    shutil.rmtree(mod, ignore_errors=True)
    java = os.path.join(mod, 'src', 'main', 'java')
    support = os.path.join(src, 'testcasesupport')
    for f in [os.path.join(support, x) for x in sorted(os.listdir(support)) if x.endswith('.java')] + files:
        rel = os.path.relpath(f, src)
        os.makedirs(os.path.dirname(os.path.join(java, rel)), exist_ok=True)
        shutil.copyfile(f, os.path.join(java, rel))
    classes = os.path.join(mod, 'target', 'classes')
    os.makedirs(classes)
    sources = [os.path.join(r, f) for r, _, fs in os.walk(java) for f in fs if f.endswith('.java')]
    argfile = os.path.join(mod, 'target', 'sources.txt')
    with open(argfile, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join('"' + s.replace('\\', '/') + '"' for s in sources))
    subprocess.run([javac, '--release', '11', '-g', '-nowarn', '-encoding', 'ISO-8859-1', '-cp', servlet_api,
                    '-d', classes, '@' + argfile], check=True)
    os.remove(argfile)
    with zipfile.ZipFile(os.path.join(mod, 'target', f'{name}.war'), 'w', zipfile.ZIP_DEFLATED) as war:
        for r, _, fs in os.walk(classes):
            for f in fs:
                p = os.path.join(r, f)
                war.write(p, 'WEB-INF/classes/' + os.path.relpath(p, classes).replace(os.sep, '/'))
    with open(os.path.join(mod, 'pom.xml'), 'w', encoding='utf-8') as fh:
        fh.write(POM.format(name=name))
    return len(sources)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('juliet')
    ap.add_argument('out')
    ap.add_argument('--servlet-api', required=True)
    ap.add_argument('--javac', default='javac')
    ap.add_argument('--cwes', default=','.join(CWES))
    a = ap.parse_args()
    src = os.path.join(a.juliet, 'src')
    for key in a.cwes.lower().split(','):
        files = cases(src, CWES[key])
        n = build(src, a.out, f'juliet-{key}', files, a.servlet_api, a.javac)
        print(f'juliet-{key}: {len(files)} test case files, {n} sources compiled', flush=True)


if __name__ == '__main__':
    sys.exit(main())
