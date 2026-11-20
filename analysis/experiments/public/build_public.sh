#!/bin/sh
# Builds a public evaluation repo inside a container. The repo is untrusted code: its Maven plugins
# run only in the container, never on the host. The sources are copied into the container (no bind
# mount of a host directory) and the build tree is copied back into a separate build dir, so the
# clone itself stays as checked out (its commit is recorded with the build).
# usage: build_public.sh <clone dir> <build dir> <maven image> [extra mvn args]
#   train-ticket (Java 8):  maven:3.9.9-eclipse-temurin-8
#   mall-swarm   (Java 17): maven:3.9.9-eclipse-temurin-17 -Ddocker.skip=true (its fabric8 plugin pushes to the author's docker host)
# output: <build dir>/**/target/*.jar and <build dir>/vkr-build.txt (commit, image digest, seconds)
set -e
src=$1
dst=$2
img=$3
shift 3   # the rest are extra Maven arguments, e.g. -Ddocker.skip=true for an image-building plugin
[ -d "$src/.git" ] || { echo "not a git clone: $src" >&2; exit 2; }
rm -rf "$dst"
mkdir -p "$dst"
docker pull -q "$img" > /dev/null
digest=$(docker image inspect --format '{{index .RepoDigests 0}}' "$img")
commit=$(git -C "$src" rev-parse HEAD)
export MSYS_NO_PATHCONV=1   # from here on Git Bash must not rewrite the container paths
src=$(cygpath -w "$src")
dst=$(cygpath -w "$dst")
# the Maven cache is a named volume shared by the builds, never the host ~/.m2
cid=$(docker create --memory 6g --cpus 4 -v vkr-public-m2:/root/.m2 -w /src "$img" \
    mvn -B -q -DskipTests -Dmaven.test.skip=true "$@" package)
trap 'docker rm -f "$cid" > /dev/null 2>&1' EXIT
docker cp "$src/." "$cid:/src"
start=$(date +%s)
status=0
docker start -a "$cid" || status=$?
seconds=$(( $(date +%s) - start ))
docker cp "$cid:/src/." "$dst"
echo "commit=$commit image=$digest seconds=$seconds exit=$status" | tee "$dst/vkr-build.txt"
exit $status
