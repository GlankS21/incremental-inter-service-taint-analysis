#!/bin/sh
# usage: run_taint.sh <service> <workdir> <taint.yml> <outdir> <log> [cs]
# env: MICANS_JAR (required: Micans build jar), JAVA_HOME (required: JDK running the analysis),
#      VKR_HEAP (max heap, default 24g), VKR_DEBUG (DebugDump filter),
#      VKR_WORK (work dir with the JRE model, default work/), VKR_JRE_HOME (a prebuilt JRE model instead),
#      VKR_WORLD_CACHE (off|rw|ro, ADR-TCI-3: rw saves and loads the serialized World, ro only
#      loads — a CI run on a PR must not write), VKR_SKIP_TFG (skip the taint-flow-graph dump,
#      default true: the CI path reads only the flow lines)
# incremental.py sets JAVA_HOME, MICANS_JAR and VKR_HEAP from vkr-taint.yml (toolchain, analysis.heap).
T=$(cd "$(dirname "$0")/.." && pwd)
: "${JAVA_HOME:?JAVA_HOME is not set (a JDK home; vkr-taint.yml toolchain.java-home)}"
: "${MICANS_JAR:?MICANS_JAR is not set (the Micans build jar; vkr-taint.yml toolchain.micans-jar)}"
[ -f "$MICANS_JAR" ] || { echo "MICANS_JAR: file not found: $MICANS_JAR" >&2; exit 2; }
# Git Bash on Windows: the JVM needs Windows paths and ';' between classpath entries; Linux: as is
if command -v cygpath > /dev/null 2>&1; then
  wp() { cygpath -w "$1"; }; CPSEP=';'
else
  wp() { printf '%s' "$1"; }; CPSEP=':'
fi
MJ=$(wp "$MICANS_JAR")
LC=$(wp "$T/launcher/classes")
rm -rf "$4"
# Tai-e resolves the JRE model (java-benchmarks/JREs/jre1.8/rt.jar) relative to the working directory
cd "${VKR_JRE_HOME:-${VKR_WORK:-$T/work}/jre-home}" || exit 2
start=$(date +%s)
# zip64 validation (JDK 21+, JDK-8302483) rejects some old library jars, e.g. aspectjweaver-1.8.10
# in train-ticket 2019; the check is off so their classes are read as before
"$JAVA_HOME/bin/java" -Xmx${VKR_HEAP:-24g} -Xss512m ${VKR_DEBUG:+-Dvkr.debug=$VKR_DEBUG} \
  -Djdk.util.zip.disableZip64ExtraFieldValidation=true \
  ${VKR_WORLD_CACHE:+-Dvkr.worldCache=$VKR_WORLD_CACHE} \
  -Dvkr.skipTaintGraph=${VKR_SKIP_TFG:-true} \
  ${VKR_RESPONSE_SINKS:+-Dvkr.responseSinks=$VKR_RESPONSE_SINKS} \
  -cp "$LC$CPSEP$MJ" vkr.MicansTaintMain "$1" \
  "$(wp "$2/app"),$(wp "$2/common")" "$(wp "$2/lib"),@$(wp "$2/libjars.txt")" "$(wp "$3")" "$(wp "$4")" \
  ${6:-ci} > "$5" 2>&1
echo "exit=$? seconds=$(( $(date +%s) - start ))" | tee -a "$5"
