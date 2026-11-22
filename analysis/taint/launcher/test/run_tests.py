"""Compiles the launcher and its unit tests, then runs the tests through the JUnit platform
launcher API.

Works on a clean machine with one command (JAVA_HOME must point to a JDK 17+):
    python analysis/taint/launcher/test/run_tests.py
The junit-platform-console-standalone jar (launcher + jupiter engine in one file, used from the
classpath, not via -jar) is taken from the local Maven repository when present and downloaded from
Maven Central otherwise; it is never committed.

usage: run_tests.py [--junit-version 1.12.2]
"""
import argparse
import glob
import os
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(os.path.dirname(HERE), 'src')
MICANS_JAR = os.environ.get('MICANS_JAR', 'C:/Users/asgat/OneDrive/Máy tính/WorkSpace/ITMO - Thsi/'
                            'vkr-artifacts/miscans/src-extract/micans-source/build/micans.jar')
JAVA_HOME = os.environ.get('JAVA_HOME', 'C:/Program Files/Java/jdk-24')
CENTRAL = ('https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/'
           '{v}/junit-platform-console-standalone-{v}.jar')
M2_PATH = ('~/.m2/repository/org/junit/platform/junit-platform-console-standalone/'
           '{v}/junit-platform-console-standalone-{v}.jar')


def console_jar(version):
    jar = os.path.expanduser(M2_PATH.format(v=version))
    if not os.path.exists(jar):
        os.makedirs(os.path.dirname(jar), exist_ok=True)
        print('downloading junit-platform-console-standalone', version)
        urllib.request.urlretrieve(CENTRAL.format(v=version), jar)
    return jar


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--junit-version', default='1.12.2')
    a = ap.parse_args()
    jar = console_jar(a.junit_version)
    out = os.path.join(os.path.dirname(HERE), 'classes-test')
    src = glob.glob(os.path.join(SRC, '**', '*.java'), recursive=True)
    tests = glob.glob(os.path.join(HERE, '**', '*.java'), recursive=True)
    subprocess.run([os.path.join(JAVA_HOME, 'bin', 'javac'), '--release', '17', '-nowarn',
                    '-cp', jar + os.pathsep + MICANS_JAR, '-d', out, *src, *tests], check=True)
    code = subprocess.run([os.path.join(JAVA_HOME, 'bin', 'java'), '-cp',
                           out + os.pathsep + jar + os.pathsep + MICANS_JAR, 'RunTests']).returncode
    sys.exit(code)


if __name__ == '__main__':
    main()
