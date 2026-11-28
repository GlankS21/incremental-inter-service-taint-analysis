#!/bin/sh
# Regression gate of a change to the analysis: runs Juliet, Securibench Micro and OWASP BenchmarkJava
# with the tool of this checkout, scores them per test case and compares the scores with
# results/gate-baseline.json (precision_gate.py). A group (category, directory, CWE) that loses a
# true positive or gains a false positive fails the gate. About 18 minutes with a 24g heap.
#
# usage: run_gate.sh <tag> [--freeze]   (--freeze writes the scores as the new baseline)
# env:   VKR_EXP         the experiment data, with the suites built (each is untrusted code, built in
#                        a container):
#                          build-juliet-root/juliet             build_juliet.py
#                          build-sbm/securibench-micro          build_public.sh, sources in
#                          public-src/securibench-micro         (the clone)
#                          build-bench/BenchmarkJava            build_public.sh, maven:3.9.9-eclipse-temurin-17
#        JAVA_HOME       a JDK 21+; VKR_MICANS_JAR the Micans jar; VKR_HEAP (default 24g)
# output: $VKR_EXP/suites/<tag>/: per suite the report and run.log, the scores (<suite>.json) and the
#         per-test lists (<suite>.csv); the work dirs are $VKR_EXP/suites-work/<suite> (emptied first)
# exit:   precision_gate.py's: 0 when the gate passes
set -u
TAG=${1:?usage: run_gate.sh <tag> [--freeze]}
freeze=
[ "${2:-}" = --freeze ] && freeze=--freeze
: "${VKR_EXP:?set VKR_EXP to the experiment data dir}"
A=$(cd "$(dirname "$0")/../.." && pwd)   # analysis/
P="$A/experiments/public"
PY=${VKR_PYTHON:-python3}
O="$VKR_EXP/suites/$TAG"
rm -rf "$O"
mkdir -p "$O"
export VKR_HEAP=${VKR_HEAP:-24g}

run() { # suite project-root config build-root
  rm -rf "$VKR_EXP/suites-work/$1"
  mkdir -p "$O/$1"
  start=$(date +%s)
  VKR_WORK="$VKR_EXP/suites-work/$1" VKR_PUBLIC_BUILD="$4" sh "$A/vkr-taint" "$2" --config "$3" --build none \
    --no-gate --out "$O/$1" > "$O/$1/run.log" 2>&1
  echo "$1: exit $?, $(( $(date +%s) - start )) s, $(grep -E 'findings, ' "$O/$1/run.log" | tail -1)"
}
run juliet "$VKR_EXP/build-juliet-root/juliet" "$P/juliet.vkr-taint.yml" "$VKR_EXP/build-juliet-root"
run securibench "$VKR_EXP/build-sbm/securibench-micro" "$P/securibench-micro.vkr-taint.yml" "$VKR_EXP/build-sbm"
run benchmark "$VKR_EXP/build-bench/BenchmarkJava" "$P/benchmarkjava.vkr-taint.yml" "$VKR_EXP/build-bench"

"$PY" -I "$P/juliet_score.py" "$O/juliet/vkr-taint-report.json" --src "$VKR_EXP/build-juliet-root/juliet" \
  --json "$O/juliet.json" --list "$O/juliet.csv"
"$PY" -I "$P/securibench_score.py" "$O/securibench/vkr-taint-report.json" "$VKR_EXP/public-src/securibench-micro/src" \
  --exclude Basic40 --json "$O/securibench.json" --list "$O/securibench.csv"
"$PY" -I "$P/benchmark_score.py" "$O/benchmark/vkr-taint-report.json" \
  "$VKR_EXP/build-bench/BenchmarkJava/expectedresults-1.2.csv" --json "$O/benchmark.json" --flagged "$O/benchmark.csv"
"$PY" -I "$P/precision_gate.py" --baseline "$P/results/gate-baseline.json" --benchmark "$O/benchmark.json" \
  --securibench "$O/securibench.json" --juliet "$O/juliet.json" $freeze
