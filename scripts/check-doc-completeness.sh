#!/usr/bin/env bash
set -euo pipefail

BINDING_FILE="src/main/scala/io/github/lene/optix/api/NativeOptiXApi.scala"

if [ ! -f "$BINDING_FILE" ]; then
  echo "ERROR: Binding file not found: $BINDING_FILE"
  exit 1
fi

ERRORS=0
prev=""
lineno=0

while IFS= read -r line; do
  lineno=$((lineno + 1))

  # Match @native def lines (inline annotation style), skip private/protected
  if echo "$line" | grep -qE '^\s+@native\s+def\s+[a-z]'; then
    if ! echo "$line" | grep -qE '^\s+(private|protected)'; then
      # Check if the previous non-blank line closes a Scaladoc comment
      if ! echo "$prev" | grep -qF '*/'; then
        method=$(echo "$line" | sed 's/.*def //;s/[:(].*//')
        echo "MISSING DOC at line $lineno: def $method"
        ERRORS=$((ERRORS + 1))
      fi
    fi
  fi

  # For multi-line @native defs: @native on its own line, def on next
  if echo "$line" | grep -qE '^\s+@native\s*$'; then
    if ! echo "$prev" | grep -qF '*/'; then
      echo "MISSING DOC before @native at line $lineno"
      ERRORS=$((ERRORS + 1))
    fi
  fi

  # Update prev, skipping blank lines
  if [ -n "$(echo "$line" | tr -d ' \t')" ]; then
    prev="$line"
  fi
done < "$BINDING_FILE"

if [ "$ERRORS" -gt 0 ]; then
  echo ""
  echo "FAIL: $ERRORS public method(s) missing Scaladoc"
  exit 1
fi
echo "OK: All public methods have Scaladoc"
