#!/bin/sh
# Suite: preflight (Sprint 36 B1). CUDA/OptiX environment (warn-only — the real
# publish-guard backstop is build.sbt's stub check) + changelog entry + version/tag
# consistency, extracted verbatim from the pre-push hook's former inline checks.
set -u
. ./standards/hooks/lib.sh

RED_TEXT='\e[38;5;196m'
GREEN_TEXT='\e[38;5;46m'
YELLOW_TEXT='\e[38;5;226m'
RESET_TEXT='\e[0m'

echo "=== Environment ==="
if [ -n "${CUDA_HOME:-}" ] && [ -x "$CUDA_HOME/bin/nvcc" ]; then
  echo "CUDA_HOME: ${GREEN_TEXT}$CUDA_HOME${RESET_TEXT}"
  if [ -n "${OPTIX_ROOT:-}" ] && [ -f "$OPTIX_ROOT/include/optix.h" ]; then
    echo "OPTIX_ROOT: ${GREEN_TEXT}$OPTIX_ROOT${RESET_TEXT}"
  else
    echo "${YELLOW_TEXT}OPTIX_ROOT: not set or incomplete${RESET_TEXT}"
  fi
else
  echo "${YELLOW_TEXT}CUDA not available — native build will produce stub. Publishing is blocked by build.sbt.${RESET_TEXT}"
fi

echo "=== Changelog & Version ==="
VERSION=$(grep 'version :=' build.sbt | cut -d'"' -f2)
if [ -z "$VERSION" ]; then
  echo "Changelog: ${RED_TEXT}FAILED${RESET_TEXT} (cannot read version from build.sbt)"
  suite_fail preflight 1 "version-unreadable"
  exit 1
fi

MAIN_VERSION=$(git show origin/main:build.sbt 2>/dev/null | grep 'version :=' | cut -d'"' -f2)
VERSION_BUMPED=0
[ "$VERSION" != "$MAIN_VERSION" ] && VERSION_BUMPED=1

if ! grep -qF "## [$VERSION]" CHANGELOG.md; then
  echo "Changelog: ${RED_TEXT}FAILED${RESET_TEXT} (missing '## [$VERSION]' entry)"
  suite_fail preflight 1 "changelog-entry-missing"
  exit 1
elif ! grep -qF "[$VERSION]:" CHANGELOG.md; then
  echo "Changelog: ${RED_TEXT}FAILED${RESET_TEXT} (missing link '[$VERSION]:' at bottom)"
  suite_fail preflight 1 "changelog-link-missing"
  exit 1
elif [ "$VERSION_BUMPED" -eq 1 ] && ! grep -qF "## [$VERSION] - $(date +%Y-%m-%d)" CHANGELOG.md; then
  echo "Changelog: ${RED_TEXT}FAILED${RESET_TEXT} (new version $VERSION needs today's date $(date +%Y-%m-%d))"
  suite_fail preflight 1 "changelog-date"
  exit 1
elif [ "$VERSION_BUMPED" -eq 1 ] && git tag | grep -q "^${VERSION}$"; then
  echo "Version: ${RED_TEXT}FAILED${RESET_TEXT} (tag $VERSION already exists — bump version)"
  suite_fail preflight 1 "tag-exists"
  exit 1
else
  echo "Changelog & Version: ${GREEN_TEXT}OK${RESET_TEXT}"
fi

suite_pass preflight
