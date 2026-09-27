#!/bin/sh
# Suite: perf. Runs the Perf-tagged timing gates (PerformanceSuite, ShadowSuite's shadow
# overhead) alone, right after `unit`: build.sbt excludes that tag from every other test run
# and PERF_ONLY=1 selects only it. Each gate times its subject against a reference in
# interleaved rounds (io.github.lene.qa.RelativeBenchmark); a gate whose measurement is too
# noisy to judge is canceled and turns the suite into a visible SKIP, never a failure.
set -u
. ./standards/hooks/lib.sh

# A busy GPU makes timings meaningless: skip, in CI too (`unit` still enforces the GPU there).
gpu_preflight_or_skip perf --skip-in-ci || exit 0

LOG=$(mktemp)
echo "=== Performance gates ==="
{ PERF_ONLY=1 sbt "testOnly *" 2>&1; echo "SBT_TEST_RC=$?"; } | tee "$LOG"
RC=$(sed 's/\x1b\[[0-9;]*[A-Za-z]//g' "$LOG" | sed -n 's/^SBT_TEST_RC=\([0-9]*\)$/\1/p' | tail -n 1)

perf_suite_verdict perf "$LOG" "${RC:-1}"
STATUS=$?
rm -f "$LOG"
exit "$STATUS"
