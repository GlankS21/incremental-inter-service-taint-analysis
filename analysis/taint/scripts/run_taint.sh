#!/bin/sh
# usage: run_taint.sh <service> <workdir> <taint.yml> <outdir> <log> [cs]
# env: MICANS_JAR (required: Micans build jar), JAVA_HOME (required: JDK running the analysis),
#      VKR_HEAP (max heap, default 24g), VKR_DEBUG (DebugDump filter),
#      VKR_WORK (work dir with the JRE model, default work/)
# incremental.py sets JAVA_HOME, MICANS_JAR and VKR_HEAP from vkr-taint.yml (toolchain, analysis.heap).
T=$(cd "$(dirname "$0")/.." && pwd)
: "${JAVA_HOME:?JAVA_HOME is not set (a JDK home; vkr-taint.yml toolchain.java-home)}"
: "${MICANS_JAR:?MICANS_JAR is not set (the Micans build jar; vkr-taint.yml toolchain.micans-jar)}"
[ -f "$MICANS_JAR" ] || { echo "MICANS_JAR: file not found: $MICANS_JAR" >&2; exit 2; }
W=$(cygpath -w "$2")
MJ=$(cygpath -w "$MICANS_JAR")
LC=$(cygpath -w "$T/launcher/classes")
rm -rf "$4"
# Tai-e resolves the JRE model (java-benchmarks/JREs/jre1.8/rt.jar) relative to the working directory
cd "${VKR_WORK:-$T/work}/jre-home" || exit 2
start=$(date +%s)
"$JAVA_HOME/bin/java" -Xmx${VKR_HEAP:-24g} -Xss512m ${VKR_DEBUG:+-Dvkr.debug=$VKR_DEBUG} -cp "$LC;$MJ" vkr.MicansTaintMain "$1" \
  "$W\app,$W\common" "$W\lib,@$W\libjars.txt" "$(cygpath -w "$3")" "$(cygpath -w "$4")" ${6:-ci} > "$5" 2>&1
echo "exit=$? seconds=$(( $(date +%s) - start ))" | tee -a "$5"
