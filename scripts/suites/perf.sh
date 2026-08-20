#!/bin/sh
# Suite: perf (Sprint 36 B1/D2). Release-tier wrapper around PerformanceSuite.scala's
# ratio-based FPS gates (calibrationFps same-run probe, Sprint 36 D2). Note: this suite
# script is new, but PerformanceSuite itself already runs on every push today via
# unit.sh's plain `sbt test` — no Slow-tag filter exists anywhere in build.sbt/CI to
# exclude it. This script exists so perf can be invoked and reasoned about on its own
# (release-checklist, manual re-runs), not because the suite was previously unexercised.
set -u
. ./standards/hooks/lib.sh

gpu_preflight_or_skip perf || exit 1

LOG=$(mktemp)
echo "=== Performance ==="
{ sbt "testOnly io.github.lene.optix.PerformanceSuite" 2>&1; echo "SBT_TEST_RC=$?"; } | tee "$LOG"

if grep -q '^SBT_TEST_RC=0$' "$LOG"; then
  echo "Performance: OK"
  suite_pass perf
  rm -f "$LOG"
  exit 0
fi

echo "Performance: FAILED"
FAILED_LINES=$(grep '\*\*\* FAILED \*\*\*' "$LOG" | sed 's/^\[info\] *//; s/ \*\*\* FAILED \*\*\*.*//')
COUNT=$(printf '%s\n' "$FAILED_LINES" | grep -c . || true)
rm -f "$LOG"

if [ "$COUNT" -eq 0 ]; then
  suite_fail perf 1 "build-or-unknown"
else
  NAMES=$(printf '%s\n' "$FAILED_LINES" | paste -sd';' -)
  suite_fail perf "$COUNT" "$NAMES"
fi
exit 1
