#!/bin/sh
# Builds the patched micans.jar from the Micans source of the paper artifact (Zenodo 10.5281/zenodo.15050505,
# "Bridge the Islands: Pointer Analysis for Microservice Systems", AGPL-3.0). Micans is not redistributed
# by this repository: the source is downloaded (or given), checked against its sha256, patched with the
# patches of this directory, and built.
# usage: build_micans.sh <out micans.jar> [micans-source.tar.xz]   (default: download from Zenodo)
# needs: JDK 17 as JAVA_HOME (the Gradle toolchain of Micans), curl, xz, tar, patch; network for Gradle
set -e
OUT=$1
SRC_TAR=$2
URL=https://zenodo.org/api/records/15050505/files/micans-source.tar.xz/content
SHA256=a098e737e50cfe648ff78d77ea7205afb6404e1bd9eaa323c8da9843809d7a01
PATCHES="micans-world-cache micans-phantom-beans micans-static-base-transfer micans-dangling-classes"
[ -n "$OUT" ] || { echo "usage: build_micans.sh <out micans.jar> [micans-source.tar.xz]" >&2; exit 2; }
: "${JAVA_HOME:?JAVA_HOME is not set (JDK 17)}"
HERE=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
if [ -z "$SRC_TAR" ]; then
    SRC_TAR="$TMP/micans-source.tar.xz"
    curl -fsSL -o "$SRC_TAR" "$URL"
fi
echo "$SHA256  $SRC_TAR" | sha256sum -c - > /dev/null || { echo "sha256 mismatch: $SRC_TAR" >&2; exit 1; }
xz -dc "$SRC_TAR" | tar -x -C "$TMP"
cd "$TMP/micans-source"
for p in $PATCHES; do
    patch -p1 --quiet < "$HERE/$p.patch"
done
sh gradlew --no-daemon -q packageMicans
mkdir -p "$(dirname "$OUT")"
cp build/micans.jar "$OUT"
echo "micans.jar: $(sha256sum "$OUT" | cut -d' ' -f1) (source $SHA256, patches: $PATCHES)"
