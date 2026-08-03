#!/usr/bin/env bash
set -euo pipefail

# F12 (Sprint 35 Task 3.4) fitness function: no raw logging in production native.
#
# Production native code must write through OPTIX_LOG(level) (src/main/native/include/
# OptixLogging.h), never raw std::cerr / std::cout / std::clog / printf / fprintf. That keeps a
# released optix-jni from spamming the host application's console and gives every native write a
# single env-gated sink. This script fails if a banned write appears in any production
# translation unit, and is run by the pre-push hook and CI.
#
# Exempt: the tests/ tree and standalone_test.cpp (test-only), stb_image_impl.cpp (vendored
# third-party). The sink header itself is the one allowed place for raw std::cerr/std::cout; it
# is a .h and is not scanned here (only .cpp/.cu are).

BANNED='std::cerr|std::cout|std::clog|(^|[^[:alnum:]_])printf[[:space:]]*\(|(^|[^[:alnum:]_])fprintf[[:space:]]*\('
EXEMPT='src/main/native/tests/|standalone_test\.cpp|stb_image_impl\.cpp'

mapfile -t files < <(find src/main/native \( -name '*.cpp' -o -name '*.cu' \) \
  | grep -vE "$EXEMPT" | sort)

violations=0
for f in "${files[@]}"; do
  if hits=$(grep -nE "$BANNED" "$f"); then
    echo "FAIL: raw logging in $f — use OPTIX_LOG(level) instead:"
    echo "$hits" | sed 's/^/  /'
    violations=$((violations + 1))
  fi
done

if [ "$violations" -gt 0 ]; then
  echo ""
  echo "FAIL: $violations production native file(s) use raw logging (see OptixLogging.h)"
  exit 1
fi

echo "OK: no raw logging in production native (${#files[@]} files scanned)"
