#!/bin/sh
# Suite: native-checks (Sprint 36 B1/B4). cppcheck (attempted whenever native code
# changed and cppcheck is installed — extracted verbatim from the pre-push hook) +
# clang-tidy (extracted verbatim from the ci.yml clang-tidy job's check step). CI
# configures target/cmake-clang-check via a dedicated CMake step before calling this
# suite (see .github/workflows/ci.yml); locally, clang-tidy self-skips rather than
# paying that CMake-configure cost on every push — it stays CI-only in practice, same
# as before this suite existed, just now invoked through one script instead of two
# separate CI jobs (B4 folds the old cppcheck + clang-tidy jobs into this one).
set -u
. ./standards/hooks/lib.sh

STATUS=0
FAILED=""

# --- cppcheck ---
if [ "${HAS_NATIVE:-0}" -eq 0 ]; then
  echo "cppcheck: skipped (no native changes)"
elif ! command -v cppcheck >/dev/null 2>&1; then
  echo "cppcheck: not installed — skipping (runs in CI)"
else
  echo "=== cppcheck ==="
  if find src/main/native \( -name "*.cpp" -o -name "*.h" \) | \
      xargs cppcheck --error-exitcode=1 --enable=warning,style \
      --language=c++ \
      --suppressions-list=.cppcheck-suppress 2>&1; then
    echo "cppcheck: OK"
  else
    echo "cppcheck: FAILED"
    STATUS=1
    FAILED="cppcheck"
  fi
fi

# --- clang-tidy ---
BUILD_DIR="target/cmake-clang-check"
if [ ! -f "$BUILD_DIR/compile_commands.json" ]; then
  echo "clang-tidy: skipped (no $BUILD_DIR/compile_commands.json — CI configures this before calling this suite)"
elif ! command -v clang-tidy >/dev/null 2>&1; then
  echo "clang-tidy: not installed — skipping"
else
  echo "=== clang-tidy ==="
  CPP_FILES=$(find src/main/native -name "*.cpp")
  gcc_install_dir="$(dirname "$(g++ -print-file-name=include)")"
  # shellcheck disable=SC2086
  if clang-tidy -p "$BUILD_DIR" --config-file=.clang-tidy \
      --extra-arg-before=--gcc-install-dir="$gcc_install_dir" \
      $CPP_FILES; then
    echo "clang-tidy: OK"
  else
    echo "clang-tidy: FAILED"
    STATUS=1
    FAILED="${FAILED:+$FAILED;}clang-tidy"
  fi
fi

if [ "$STATUS" -eq 0 ]; then
  suite_pass native-checks
else
  suite_fail native-checks 1 "$FAILED"
fi
exit "$STATUS"
