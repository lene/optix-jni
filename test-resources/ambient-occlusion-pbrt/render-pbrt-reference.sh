#!/usr/bin/env bash
# Renders the committed pbrt-v4 reference of the ambient-occlusion twin scene (optix-jni#106).
#
# MANUAL ONLY: never run by hooks or the test suite, which read the committed reference
# (sponge-on-floor_400x300.pfm.gz).
#
# pbrt-v4 is built from source in user space (no root):
#   git clone --recursive https://github.com/mmp/pbrt-v4 ~/src/pbrt-v4
#   cmake -S ~/src/pbrt-v4 -B ~/src/pbrt-v4/build -DCMAKE_BUILD_TYPE=Release
#   cmake --build ~/src/pbrt-v4/build -j
#
# Usage: render-pbrt-reference.sh   (PBRT_BIN=<dir with pbrt and imgtool> to override ~/src/pbrt-v4/build)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="${PBRT_BIN:-$HOME/src/pbrt-v4/build}"
SEED="${SEED:-0}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$HERE/sponge-on-floor.pbrt" "$WORK/"
( cd "$WORK" && "$BIN/pbrt" --seed "$SEED" --quiet sponge-on-floor.pbrt )
"$BIN/imgtool" convert --outfile "$WORK/sponge-on-floor.pfm" "$WORK/sponge-on-floor.exr"
gzip -9 -c "$WORK/sponge-on-floor.pfm" > "$HERE/sponge-on-floor_400x300.pfm.gz"

{
  echo "scene:        sponge-on-floor.pbrt"
  echo "resolution:   400x300"
  echo "pbrt_commit:  $(git -C "$BIN/.." rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "pbrt_sha256:  $(sha256sum "$BIN/pbrt" | awk '{print $1}')"
  echo "seed:         $SEED"
  echo "scene_sha256: $(sha256sum "$HERE/sponge-on-floor.pbrt" | awk '{print $1}')"
  echo "rendered:     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$HERE/sponge-on-floor_400x300.manifest.txt"
echo "wrote $HERE/sponge-on-floor_400x300.pfm.gz"
