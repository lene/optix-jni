#!/bin/sh
# Suite: policy (Sprint 36 B1). Test-Change trailer gate + rendering-discipline gate.
# Both hooks were already vendored into this repo (shared/standards/manifest.txt) but
# never invoked here before this suite existed — see docs/QA_INCIDENTS.md.
#
# Reads $QA_RANGES: newline-separated rev-ranges, set by the caller (pre-push hook or
# CI wrapper) from standards/hooks/lib.sh's push_ranges.
set -u
. ./standards/hooks/lib.sh

if [ -z "${QA_RANGES:-}" ]; then
  suite_skip policy "no push ranges available"
  exit 0
fi

STATUS=0
FAILED=""
if ! ./standards/hooks/check-test-justification.sh $QA_RANGES; then
  STATUS=1
  FAILED="test-justification"
fi
if ! ./standards/hooks/check-rendering-discipline.sh $QA_RANGES; then
  STATUS=1
  FAILED="${FAILED:+$FAILED;}rendering-discipline"
fi
if ! ./standards/hooks/check-log-verbosity.sh $QA_RANGES; then
  STATUS=1
  FAILED="${FAILED:+$FAILED;}log-verbosity"
fi

if [ "$STATUS" -eq 0 ]; then
  suite_pass policy
else
  suite_fail policy 1 "$FAILED"
fi
exit "$STATUS"
