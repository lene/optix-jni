#!/bin/sh
# Suite: lint (Sprint 36 B1). Scalafix check, extracted verbatim.
set -u
. ./standards/hooks/lib.sh

echo "=== Scalafix ==="
if sbt "scalafix --check"; then
  echo "Scalafix: OK"
  suite_pass lint
else
  echo "Scalafix: FAILED"
  suite_fail lint 1 "scalafix"
  exit 1
fi
