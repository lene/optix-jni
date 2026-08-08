#!/bin/sh
# Suite: native-logging (Sprint 36 B1). F12: no raw std::cerr/std::cout/printf in
# production native code, extracted verbatim.
set -u
. ./standards/hooks/lib.sh

if [ "${HAS_NATIVE:-0}" -eq 0 ]; then
  suite_skip native-logging "no native changes"
  exit 0
fi

echo "=== native logging ==="
if bash scripts/check-native-logging.sh; then
  echo "native logging: OK"
  suite_pass native-logging
else
  echo "native logging: FAILED"
  suite_fail native-logging 1 "native-logging"
  exit 1
fi
