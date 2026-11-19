#!/bin/sh
# Builds the class layout of work/ for one service: the JRE model (once) and the service's extracted
# application and library classes. The taint configs of the workdir are generated afterwards by
# vkr_prepare.py (which calls this script with the paths vkr_config.py resolved from vkr-taint.yml).
# usage: prepare.sh <service> <classes dir> <spring boot jar>
#        prepare.sh --jre-only
# env: JAVA_HOME (required: JDK whose modules form the JRE model), VKR_SHARED_JARS (comma-separated
#      artifact names of the project's shared modules, extracted as application code),
#      VKR_WORK (work dir, default work/; keep it out of synced folders such as OneDrive)
set -e
T=$(cd "$(dirname "$0")/.." && pwd)
: "${JAVA_HOME:?JAVA_HOME is not set (a JDK home; vkr-taint.yml toolchain.java-home)}"
export JAVA_HOME
K=${VKR_WORK:-"$T/work"}
if [ "$1" != "--jre-only" ]; then
    svc=$1
    CLASSES=$2
    JAR=$3
    [ -n "$svc" ] && [ -d "$CLASSES" ] && [ -f "$JAR" ] || {
        echo "usage: prepare.sh <service> <classes dir> <spring boot jar> (got '$svc' '$CLASSES' '$JAR')" >&2
        exit 2
    }
fi
mkdir -p "$K"

# JRE model: JDK classes downgraded to class version 61 for Micans' ASM 9.4, packed where Tai-e
# looks for a Java 8 rt.jar; record bootstraps resolve through an analysis-only ObjectMethods stub
RT="$K/jre-home/java-benchmarks/JREs/jre1.8/rt.jar"
if [ ! -f "$RT" ]; then
    MODS="java.base java.logging java.sql java.naming java.desktop java.management java.xml java.instrument java.compiler jdk.unsupported java.transaction.xa java.net.http java.scripting java.security.jgss java.rmi java.datatransfer java.prefs"
    INC=$(for m in $MODS; do printf "regex:/%s/.*," "$m"; done)
    rm -rf "$K/jdkx" "$K/stubs"
    "$JAVA_HOME/bin/jimage" extract --include "${INC%,}" --dir "$(cygpath -w "$K/jdkx")" "$(cygpath -w "$JAVA_HOME/lib/modules")"
    (cd "$T/jdk-stubs" && "$JAVA_HOME/bin/javac" --release 8 -d "$K/stubs" java/lang/invoke/*.java java/lang/runtime/*.java)
    python -I "$T/scripts/pack_jre.py" "$K/jdkx" "$RT"
    python -I - "$RT" "$K/stubs/java/lang/runtime/ObjectMethods.class" <<'EOF'
import sys, zipfile, os
rt, stub = sys.argv[1], sys.argv[2]
tmp = rt + '.tmp'
name = 'java/lang/runtime/ObjectMethods.class'
with zipfile.ZipFile(rt) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, open(stub, 'rb').read() if item.filename == name else zin.read(item.filename))
os.replace(tmp, rt)
print('replaced', name)
EOF
fi
[ "$1" = "--jre-only" ] && exit 0

W="$K/$svc"
if [ ! -d "$W/app" ]; then
    rm -rf "$W"
    mkdir -p "$W/bootjar"
    cp -r "$CLASSES" "$W/app"
    (cd "$W/bootjar" && unzip -q "$JAR" 'BOOT-INF/lib/*')
    python -I "$T/scripts/prep_micans.py" "$W"
fi
echo "$svc: $W"
