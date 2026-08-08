#!/bin/sh
# Suite-script parity check (Sprint 36 B5). Every script under scripts/suites/ must be
# referenced by at least one of: the pre-push hook's suite list, a ci.yml step. Catches
# orphaned suite scripts (written but never wired in) and dangling suite names (invoked
# by the hook with no matching script file) — same "known gap, not silent" discipline as
# check-standards-drift.sh. Deliberately narrower than full hook-vs-CI set equality: it
# doesn't require every suite the hook runs to also run in CI, or vice versa.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SUITES_DIR="$ROOT/scripts/suites"
HOOK="$ROOT/.git_hooks/pre-push"
CI="$ROOT/.github/workflows/ci.yml"

FAIL=0

referenced() {
  name="$1"
  grep -qE "(^|[^A-Za-z0-9_-])${name}(\\.sh)?([^A-Za-z0-9_-]|\$)" "$HOOK" 2>/dev/null && return 0
  grep -qE "scripts/suites/${name}\\.sh" "$CI" 2>/dev/null && return 0
  return 1
}

for script in "$SUITES_DIR"/*.sh; do
  name=$(basename "$script" .sh)
  if ! referenced "$name"; then
    echo "ORPHAN: scripts/suites/${name}.sh is not referenced by .git_hooks/pre-push or .github/workflows/ci.yml"
    FAIL=1
  fi
done

if [ -f "$HOOK" ]; then
  SUITE_LINE=$(grep -oE 'qa-runner\.sh [a-zA-Z0-9_ -]+' "$HOOK" | tail -n 1 | sed 's/^qa-runner\.sh //')
  for name in $SUITE_LINE; do
    [ -f "$SUITES_DIR/$name.sh" ] || {
      echo "DANGLING: hook invokes suite '$name' with no scripts/suites/$name.sh"
      FAIL=1
    }
  done
fi

if [ "$FAIL" -ne 0 ]; then
  echo "Suite parity check failed." >&2
  exit 1
fi
echo "Suite parity OK: all scripts/suites/*.sh referenced, no dangling suite names."
