#!/bin/sh
# Shared QA suite runner (Sprint 36 Phase B). Canon lives here; vendored byte-identical
# into menger/optix-jni/menger-common as standards/scripts/qa-runner.sh via
# scripts/sync-standards.sh (see shared/standards/manifest.txt, shared/standards/README.md).
#
# Executes named suites from scripts/suites/<name>.sh, in the given order, and prints an
# honest final summary: a suite that was inapplicable (e.g. no Scala changed) reports
# SKIP with a reason, never PASS — replacing menger's old skip_section, which printed
# "PASSED" for stages that never ran.
#
# Usage: qa-runner.sh <suite> [<suite> ...]
#
# Each scripts/suites/<name>.sh runs to completion (its own stdout/stderr stream live)
# and, as the LAST line it prints, emits exactly one line of the form:
#   SUITE <name> PASS|FAIL|SKIP n_failed=<k> failed="<name1;name2;...>" reason="<text>"
# failed="" when n_failed=0; reason="" except for SKIP. A suite script that produces no
# such line (crash, syntax error, killed) is treated as FAIL, not silently ignored —
# same "refuse to report a clean run on missing evidence" principle as assert_detection
# in standards/hooks/lib.sh.
#
# Suite scripts decide their own relevance (reading the HAS_SCALA / HAS_NATIVE / ...
# environment variables the caller already computes and exports today) and self-skip;
# this runner always receives the repo's full, fixed suite list and does not filter it.
#
# v1 is intentionally sequential — see Sprint 36 Phase B plan for the stated tradeoff
# against the small amount of parallelism (cppcheck+clang-tidy) menger's old hook had.
set -u

if [ $# -eq 0 ]; then
  echo "usage: qa-runner.sh <suite> [<suite> ...]" >&2
  exit 2
fi

SUITES_DIR="./scripts/suites"
TOTAL=$#
N=0
OVERALL=0
RESULTS=$(mktemp)
trap 'rm -f "$RESULTS"' EXIT
TAB=$(printf '\t')

for suite in "$@"; do
  N=$((N + 1))
  echo "[$N/$TOTAL] $suite"
  script="$SUITES_DIR/$suite.sh"

  if [ ! -x "$script" ]; then
    echo "  SKIPPED (no such suite script: $script)"
    printf '%s%sSKIP%smissing script\n' "$suite" "$TAB" "$TAB" >> "$RESULTS"
    continue
  fi

  LOG=$(mktemp)
  "$script" 2>&1 | tee "$LOG"

  LINE=$(grep "^SUITE $suite " "$LOG" | tail -n 1)
  if [ -z "$LINE" ]; then
    echo "  ${suite}: FAILED — no SUITE line emitted (crashed or misbehaved)"
    printf '%s%sFAIL%sno SUITE line emitted\n' "$suite" "$TAB" "$TAB" >> "$RESULTS"
    OVERALL=1
  else
    VERDICT=$(printf '%s' "$LINE" | awk '{print $3}')
    case "$VERDICT" in
      PASS)
        printf '%s%sPASS%s\n' "$suite" "$TAB" "$TAB" >> "$RESULTS"
        ;;
      SKIP)
        REASON=$(printf '%s' "$LINE" | sed -n 's/.*reason="\([^"]*\)".*/\1/p')
        printf '%s%sSKIP%s%s\n' "$suite" "$TAB" "$TAB" "$REASON" >> "$RESULTS"
        ;;
      *)
        FAILED_NAMES=$(printf '%s' "$LINE" | sed -n 's/.*failed="\([^"]*\)".*/\1/p')
        printf '%s%sFAIL%s%s\n' "$suite" "$TAB" "$TAB" "$FAILED_NAMES" >> "$RESULTS"
        OVERALL=1
        ;;
    esac
  fi
  rm -f "$LOG"
done

echo ""
echo "=== QA summary ==="
while IFS="$TAB" read -r name verdict detail; do
  case "$verdict" in
    PASS) echo "  PASS    $name" ;;
    SKIP) echo "  SKIPPED $name ($detail)" ;;
    FAIL) echo "  FAILED  $name${detail:+ [$detail]}" ;;
  esac
done < "$RESULTS"

if [ "$OVERALL" -ne 0 ]; then
  echo ""
  echo "=== Failed suites, details ==="
  while IFS="$TAB" read -r name verdict detail; do
    [ "$verdict" = "FAIL" ] && [ -n "$detail" ] && echo "  $name: $detail"
  done < "$RESULTS"
fi

exit "$OVERALL"
