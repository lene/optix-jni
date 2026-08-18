#!/bin/sh
# Suite: unit (Sprint 36 B1). Compile (fast-fail before the slower test run) + sbt test
# (GPU tests auto-skip via assume() when no GPU is present), extracted verbatim — same
# marker-line technique as before: `sbt test | tee` returns tee's exit code, not sbt's,
# so capture sbt's real exit via a marker line that survives the tee. Adds
# failed-test-name extraction to satisfy qa-runner.sh's SUITE-line contract (O1: failed
# tests named in the final summary, not just "something failed").
set -u
. ./standards/hooks/lib.sh

# `sbt test` also exercises GpuLeakSuite and PerformanceSuite (real CUDA calls) in the
# same JVM run as the plain unit tests — no Slow-tag filter exists to split them out
# (Sprint 36 D1). Previously unguarded here; menger's unit.sh gates the same way for the
# same reason.
gpu_preflight_or_skip unit || exit 1

echo "=== Compile ==="
if ! sbt compile; then
  suite_fail unit 1 "compile"
  exit 1
fi

LOG=$(mktemp)
echo "=== Tests ==="
{ sbt test 2>&1; echo "SBT_TEST_RC=$?"; } | tee "$LOG"

# sbt's last write before exiting is often a bare ANSI erase-to-end-of-line sequence with no
# trailing newline (e.g. \x1b[0J), which then glues onto the front of the marker echo on the
# same captured line -- the anchored grep below sees "\x1b[0JSBT_TEST_RC=0", not
# "SBT_TEST_RC=0" at start-of-line, and misreports a passing run as failed. Strip ANSI CSI
# sequences (colors, cursor/erase codes) before matching.
if sed 's/\x1b\[[0-9;]*[A-Za-z]//g' "$LOG" | grep -q '^SBT_TEST_RC=0$'; then
  echo "Tests: OK"
  suite_pass unit
  rm -f "$LOG"
  exit 0
fi

echo "Tests: FAILED"
FAILED_LINES=$(grep '\*\*\* FAILED \*\*\*' "$LOG" | sed 's/^\[info\] *//; s/ \*\*\* FAILED \*\*\*.*//')
COUNT=$(printf '%s\n' "$FAILED_LINES" | grep -c . || true)
rm -f "$LOG"

if [ "$COUNT" -eq 0 ]; then
  suite_fail unit 1 "build-or-unknown"
else
  NAMES=$(printf '%s\n' "$FAILED_LINES" | paste -sd';' -)
  suite_fail unit "$COUNT" "$NAMES"
fi
exit 1
