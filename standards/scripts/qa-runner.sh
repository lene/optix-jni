#!/bin/sh
# Shared QA suite runner (Sprint 36 Phase C: manifest + resume cache + fail-fast). Canon
# lives here; vendored byte-identical into menger/optix-jni/menger-common as
# standards/scripts/qa-runner.sh via scripts/sync-standards.sh (see
# shared/standards/manifest.txt, shared/standards/README.md).
#
# Usage: qa-runner.sh --tier <commit|push|pr|main|release> [--all] [<suite> ...]
#
# Reads ./qa-manifest.yaml (per-repo, not vendored — see menger/scripts/coverage-manifest.yaml
# for the established pattern of a repo-owned manifest). Two sections:
#   change_classes: <name>: [<ERE pattern>, ...]   (a pattern may be "@file:<path>" to read
#                                                     patterns from an existing pattern file)
#   suites: <name>: { tier: <t>, requires: [<class>, ...], automated: true|false }
# `requires: []` = always relevant. `automated: false` = no scripts/suites/<name>.sh exists
# (e.g. a still-manual release-tier check) — always reported SKIP, never invoked.
#
# A suite runs only if: its tier <= --tier ceiling, AND (requires is empty OR at least one
# required change-class matched $CHANGED_FILES), AND it isn't automated:false. Anything else
# is recorded straight into SKIP with a labeled reason — no scripts/suites/<name>.sh subshell
# is spawned for it. Before invoking a relevant suite, HAS_<CLASS> (uppercased) is exported
# for every change_classes entry, exactly as the calling hook used to compute and export
# HAS_SCALA/HAS_NATIVE/... itself — suite scripts need no changes.
#
# Each invoked scripts/suites/<name>.sh runs to completion (its own stdout/stderr stream
# live) and, as the LAST line it prints, emits exactly one line of the form:
#   SUITE <name> PASS|FAIL|SKIP n_failed=<k> failed="<name1;name2;...>" reason="<text>"
# A suite script that produces no such line (crash, syntax error, killed) is treated as
# FAIL, not silently ignored — same "refuse to report a clean run on missing evidence"
# principle as assert_detection in standards/hooks/lib.sh.
#
# Resume cache: a suite whose last live run on this exact tree was PASS is remembered at
# .git/qa-cache/<suite>-<tree-sha> and reported "CACHED PASS (tree unchanged)" without
# re-invoking its script. Never cached on FAIL or SKIP — only a proven-clean PASS is safe
# to trust later. Entries older than 14 days are pruned automatically.
#
# Fail-fast: on a TTY (interactive push) the run stops at the first FAIL and prints the
# summary so far. --all (or a non-TTY caller, e.g. CI) runs every relevant suite regardless.
set -u

usage() {
  echo "usage: qa-runner.sh --tier <commit|push|pr|main|release> [--all] [<suite> ...]" >&2
  exit 2
}

TIER=""
ALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tier) TIER="${2:-}"; shift 2 ;;
    --tier=*) TIER="${1#--tier=}"; shift ;;
    --all) ALL=1; shift ;;
    --) shift; break ;;
    -*) usage ;;
    *) break ;;
  esac
done
[ -n "$TIER" ] || usage
case "$TIER" in
  commit | push | pr | main | release) ;;
  *) usage ;;
esac

SUITES_DIR="./scripts/suites"
MANIFEST="./qa-manifest.yaml"
CACHE_DIR=".git/qa-cache"
CHANGED_FILES="${CHANGED_FILES:-}"

[ -r "$MANIFEST" ] || { echo "qa-runner.sh: missing $MANIFEST" >&2; exit 2; }

tier_rank() {
  case "$1" in
    commit) echo 0 ;;
    push) echo 1 ;;
    pr) echo 2 ;;
    main) echo 3 ;;
    release) echo 4 ;;
    *) echo 99 ;;
  esac
}
CEILING=$(tier_rank "$TIER")

MANIFEST_FLAT=$(mktemp)
RESULTS=$(mktemp)
SUITE_LIST_FILE=$(mktemp)
trap 'rm -f "$MANIFEST_FLAT" "$RESULTS" "$SUITE_LIST_FILE"' EXIT

# --- Parse the manifest into flat, tab-separated CLASS/SUITE records --------------------
# CLASS <name> <pattern>          (one line per pattern)
# SUITE <name> <tier> <requires-csv-or-dash> <automated:true|false>
# requires-csv is "-" (not empty) when requires:[] — POSIX `read` collapses consecutive
# IFS-whitespace delimiters (tab counts as whitespace even set alone), so a truly empty
# field between two tabs would silently merge with its neighbor; "-" survives the split
# and is normalized back to "" right after each read.
awk -f - "$MANIFEST" <<'AWK_EOF' > "$MANIFEST_FLAT"
BEGIN { section = ""; class = "" }
/^change_classes:[ \t]*$/ { section = "classes"; next }
/^suites:[ \t]*$/ { section = "suites"; next }
section == "classes" {
  if ($0 ~ /^  [A-Za-z0-9_-]+:[ \t]*$/) {
    class = $0
    sub(/^  /, "", class)
    sub(/:[ \t]*$/, "", class)
    next
  }
  if ($0 ~ /^    - /) {
    pat = $0
    sub(/^    - /, "", pat)
    gsub(/^'/, "", pat); gsub(/'$/, "", pat)
    gsub(/^"/, "", pat); gsub(/"$/, "", pat)
    print "CLASS\t" class "\t" pat
  }
  next
}
section == "suites" {
  if ($0 ~ /^  [A-Za-z0-9_-]+:[ \t]*\{/) {
    line = $0
    name = line
    sub(/^  /, "", name)
    sub(/:.*/, "", name)
    tier = line
    sub(/.*tier:[ \t]*/, "", tier)
    sub(/[ \t,}].*/, "", tier)
    req = ""
    if (match(line, /requires:[ \t]*\[[^]]*\]/)) {
      r = substr(line, RSTART, RLENGTH)
      sub(/requires:[ \t]*\[/, "", r)
      sub(/\][ \t]*$/, "", r)
      gsub(/[ \t]/, "", r)
      req = r
    }
    auto = "true"
    if (match(line, /automated:[ \t]*(true|false)/)) {
      a = substr(line, RSTART, RLENGTH)
      sub(/.*automated:[ \t]*/, "", a)
      auto = a
    }
    if (req == "") req = "-"
    print "SUITE\t" name "\t" tier "\t" req "\t" auto
  }
  next
}
AWK_EOF

# $1 = class name; prints an ERE alternation of all its patterns, expanding any
# "@file:<path>" entry into that pattern file's non-comment/non-blank lines.
class_pattern() {
  _pats=""
  _tmp=$(mktemp)
  grep "^CLASS	$1	" "$MANIFEST_FLAT" | cut -f3 > "$_tmp"
  while IFS= read -r _p; do
    case "$_p" in
      @file:*)
        _f=${_p#@file:}
        if [ -r "$_f" ]; then
          _filepats=$(grep -v '^[[:space:]]*\(#\|$\)' "$_f" | paste -sd'|' -)
          [ -n "$_filepats" ] && _pats="${_pats:+$_pats|}$_filepats"
        fi
        ;;
      *)
        _pats="${_pats:+$_pats|}$_p"
        ;;
    esac
  done < "$_tmp"
  rm -f "$_tmp"
  printf '%s' "$_pats"
}

# Compute and export HAS_<CLASS> for every change_classes entry, replacing what each
# hook's own grep block used to do.
for _class in $(grep '^CLASS' "$MANIFEST_FLAT" | cut -f2 | sort -u); do
  _pattern=$(class_pattern "$_class")
  _upper=$(printf '%s' "$_class" | tr 'a-z-' 'A-Z_')
  _count=0
  if [ -n "$_pattern" ]; then
    _count=$(printf '%s\n' "$CHANGED_FILES" | grep -cE "$_pattern" 2>/dev/null)
    [ -z "$_count" ] && _count=0
  fi
  eval "HAS_${_upper}=${_count}; export HAS_${_upper}"
done

# $1 = comma-separated requires list (empty = always relevant).
suite_relevant() {
  [ -z "$1" ] && return 0
  for _req in $(printf '%s' "$1" | tr ',' ' '); do
    _upper=$(printf '%s' "$_req" | tr 'a-z-' 'A-Z_')
    eval "_val=\${HAS_${_upper}:-0}"
    [ "$_val" -gt 0 ] 2>/dev/null && return 0
  done
  return 1
}

grep '^SUITE' "$MANIFEST_FLAT" > "$SUITE_LIST_FILE"

if [ $# -gt 0 ]; then
  : > "$SUITE_LIST_FILE.filtered"
  for _want in "$@"; do
    grep -m1 "^SUITE	$_want	" "$MANIFEST_FLAT" >> "$SUITE_LIST_FILE.filtered" || true
  done
  mv "$SUITE_LIST_FILE.filtered" "$SUITE_LIST_FILE"
fi

TOTAL=$(grep -c '^SUITE' "$SUITE_LIST_FILE" || true)
[ -z "$TOTAL" ] && TOTAL=0
N=0
OVERALL=0
TAB=$(printf '\t')

mkdir -p "$CACHE_DIR" 2>/dev/null || true
find "$CACHE_DIR" -type f -mtime +14 -delete 2>/dev/null || true
TREE_SHA=$(git rev-parse 'HEAD^{tree}' 2>/dev/null || echo notree)

FAILFAST=0
[ -t 1 ] && [ "$ALL" -eq 0 ] && FAILFAST=1

while IFS="$TAB" read -r _tag name tier req auto; do
  [ "$_tag" = "SUITE" ] || continue
  [ "$req" = "-" ] && req=""
  N=$((N + 1))
  echo "[$N/$TOTAL] $name"

  suite_ceiling=$(tier_rank "$tier")
  if [ "$suite_ceiling" -gt "$CEILING" ]; then
    echo "  SKIPPED (tier: $tier > $TIER)"
    printf '%s%sSKIP%stier: %s > %s\n' "$name" "$TAB" "$TAB" "$tier" "$TIER" >> "$RESULTS"
    continue
  fi

  if [ "$auto" = "false" ]; then
    echo "  SKIPPED (manual/unautomated, release-tier only)"
    printf '%s%sSKIP%smanual/unautomated, release-tier only\n' "$name" "$TAB" "$TAB" >> "$RESULTS"
    continue
  fi

  if ! suite_relevant "$req"; then
    echo "  SKIPPED (no matching changed paths)"
    printf '%s%sSKIP%sno matching changed paths\n' "$name" "$TAB" "$TAB" >> "$RESULTS"
    continue
  fi

  script="$SUITES_DIR/$name.sh"
  if [ ! -x "$script" ]; then
    echo "  SKIPPED (no such suite script: $script)"
    printf '%s%sSKIP%smissing script\n' "$name" "$TAB" "$TAB" >> "$RESULTS"
    continue
  fi

  CACHE_FILE="$CACHE_DIR/$name-$TREE_SHA"
  if [ -f "$CACHE_FILE" ] && grep -q '^verdict=PASS$' "$CACHE_FILE" 2>/dev/null; then
    echo "  CACHED PASS (tree unchanged)"
    printf '%s%sCACHED%s\n' "$name" "$TAB" "$TAB" >> "$RESULTS"
    continue
  fi

  LOG=$(mktemp)
  "$script" 2>&1 | tee "$LOG"

  LINE=$(grep "^SUITE $name " "$LOG" | tail -n 1)
  if [ -z "$LINE" ]; then
    echo "  ${name}: FAILED — no SUITE line emitted (crashed or misbehaved)"
    printf '%s%sFAIL%sno SUITE line emitted\n' "$name" "$TAB" "$TAB" >> "$RESULTS"
    OVERALL=1
    rm -f "$LOG"
    [ "$FAILFAST" -eq 1 ] && break
    continue
  fi

  VERDICT=$(printf '%s' "$LINE" | awk '{print $3}')
  case "$VERDICT" in
    PASS)
      printf '%s%sPASS%s\n' "$name" "$TAB" "$TAB" >> "$RESULTS"
      { echo "verdict=PASS"; echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "tier=$tier"; } > "$CACHE_FILE"
      ;;
    SKIP)
      REASON=$(printf '%s' "$LINE" | sed -n 's/.*reason="\([^"]*\)".*/\1/p')
      printf '%s%sSKIP%s%s\n' "$name" "$TAB" "$TAB" "$REASON" >> "$RESULTS"
      ;;
    *)
      FAILED_NAMES=$(printf '%s' "$LINE" | sed -n 's/.*failed="\([^"]*\)".*/\1/p')
      printf '%s%sFAIL%s%s\n' "$name" "$TAB" "$TAB" "$FAILED_NAMES" >> "$RESULTS"
      OVERALL=1
      ;;
  esac
  rm -f "$LOG"
  if [ "$VERDICT" != "PASS" ] && [ "$VERDICT" != "SKIP" ] && [ "$FAILFAST" -eq 1 ]; then
    break
  fi
done < "$SUITE_LIST_FILE"

echo ""
echo "=== QA summary ==="
while IFS="$TAB" read -r name verdict detail; do
  case "$verdict" in
    PASS) echo "  PASS    $name" ;;
    CACHED) echo "  CACHED  $name (tree unchanged)" ;;
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
