#!/bin/bash
# replay.sh <repo> <first> <last> <maven image> [mvn args]
# Replays <first>..<last> of the clone $LOCALAPPDATA/vkr-exp/public/<repo> with replay.py, using the
# config <repo>.vkr-taint.yml next to this script, and checks equivalence with a cold run on <last>.
# env: JAVA_HOME (JDK running Micans), VKR_MICANS_JAR (the patched Micans jar)
# e.g. replay.sh mall-swarm 9ed1453 04c442f maven:3.9.9-eclipse-temurin-17 -Ddocker.skip=true
#      replay.sh train-ticket 9d8e8d6 142d685 maven:3.9.9-eclipse-temurin-8
: "${JAVA_HOME:?JAVA_HOME is not set}" "${VKR_MICANS_JAR:?VKR_MICANS_JAR is not set}"
export PYTHONIOENCODING=utf-8
HERE=$(cd "$(dirname "$0")" && pwd)
L="$LOCALAPPDATA/vkr-exp"
r=$1; f=$2; l=$3; img=$4; shift 4
args=(); for m in "$@"; do args+=(--mvn-arg="$m"); done
mkdir -p "$L/replay/$r"
python -I "$HERE/replay.py" "$L/public/$r" "$f" "$l" \
  --config "$HERE/$r.vkr-taint.yml" --image "$img" "${args[@]}" --cold-check \
  2>&1 | tee -a "$L/replay/$r/replay.out"
