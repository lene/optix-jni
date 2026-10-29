#!/bin/sh
# Suite: mima (optix-jni#76). Binary compatibility against the last published release
# (build.sbt `mimaPreviousArtifacts`); an intentional break is a filter there, with its reason.
set -u
. ./standards/hooks/lib.sh

echo "=== MiMa ==="
if sbt -batch mimaReportBinaryIssues; then
  echo "MiMa: OK"
  suite_pass mima
else
  echo "MiMa: FAILED (unfiltered binary incompatibility with the previous release)"
  suite_fail mima 1 "mimaReportBinaryIssues"
  exit 1
fi
