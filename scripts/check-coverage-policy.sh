#!/usr/bin/env bash

set -euo pipefail

coverage_floor="60"
coverage_target="80"
max_drop_below_target="1"
baseline_file=".coverage_baseline"

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

shopt -s nullglob
reports=(
  target/scoverage-report/scoverage.xml
  target/scala-*/scoverage-report/scoverage.xml
)
shopt -u nullglob

if [ "${#reports[@]}" -eq 0 ]; then
  echo "Coverage report not found under target/scoverage-report or target/scala-*/scoverage-report" >&2
  exit 1
fi

report="${reports[0]}"
raw_rate="$(
  grep -o 'statement-rate="[^"]*"' "$report" |
    head -n 1 |
    cut -d '"' -f 2
)"

if [ -z "$raw_rate" ]; then
  echo "Could not parse statement-rate from $report" >&2
  exit 1
fi

normalize_rate() {
  awk -v rate="$1" 'BEGIN {
    if (rate <= 1) {
      rate *= 100
    }
    printf "%.2f", rate
  }'
}

coverage_rate="$(normalize_rate "$raw_rate")"

if [ -f "$baseline_file" ]; then
  baseline_rate="$(normalize_rate "$(tr -d '[:space:]' < "$baseline_file")")"
else
  baseline_rate="$coverage_rate"
  echo "No .coverage_baseline found; using current coverage as this run's baseline."
fi

drop="$(
  awk -v baseline="$baseline_rate" -v current="$coverage_rate" 'BEGIN {
    diff = baseline - current
    if (diff < 0) {
      diff = 0
    }
    printf "%.2f", diff
  }'
)"

echo "Statement coverage: current ${coverage_rate}%, baseline ${baseline_rate}%, drop ${drop}%"

if awk -v current="$coverage_rate" -v floor="$coverage_floor" \
  'BEGIN { exit current < floor ? 0 : 1 }'; then
  below_floor=0
else
  below_floor=1
fi
if [ "$below_floor" -eq 0 ]; then
  echo "Coverage failed: ${coverage_rate}% is below the ${coverage_floor}% absolute floor." >&2
  exit 1
fi

if awk -v current="$coverage_rate" -v target="$coverage_target" \
  'BEGIN { exit current < target ? 0 : 1 }'; then
  below_target=0
else
  below_target=1
fi

if awk -v drop="$drop" -v allowed="$max_drop_below_target" 'BEGIN {
    exit drop > allowed ? 0 : 1
  }'; then
  drop_exceeds_limit=0
else
  drop_exceeds_limit=1
fi

if [ "$below_target" -eq 0 ] && [ "$drop_exceeds_limit" -eq 0 ]; then
  echo \
    "Coverage failed: drop ${drop}% exceeds ${max_drop_below_target}% while below ${coverage_target}%." >&2
  exit 1
fi

echo "Coverage policy passed."
