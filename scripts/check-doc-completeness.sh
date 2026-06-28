#!/usr/bin/env bash
set -euo pipefail

# Check Scaladoc completeness on all public API Scala files in optix-jni.
# Public API: OptiXRenderer, OptiXDenoiser, NativeOptiXApi, and their companion objects.
# Private traits (OptiX*Api) are excluded — they're internal implementation detail.

FILES=(
  "src/main/scala/io/github/lene/optix/OptiXRenderer.scala"
  "src/main/scala/io/github/lene/optix/OptiXDenoiser.scala"
  "src/main/scala/io/github/lene/optix/api/NativeOptiXApi.scala"
)

TOTAL_ERRORS=0

for FILE in "${FILES[@]}"; do
  if [ ! -f "$FILE" ]; then
    echo "WARNING: File not found, skipping: $FILE"
    continue
  fi

  ERRORS=0
  prev=""
  lineno=0

  while IFS= read -r line; do
    lineno=$((lineno + 1))

    # Match @native def lines (inline annotation style), skip private/protected
    if echo "$line" | grep -qE '^\s+@native\s+def\s+[a-z]'; then
      if ! echo "$line" | grep -qE '^\s+(private|protected)'; then
        if ! echo "$prev" | grep -qF '*/'; then
          method=$(echo "$line" | sed 's/.*def //;s/[:(].*//')
          echo "  MISSING DOC at $FILE:$lineno: def $method"
          ERRORS=$((ERRORS + 1))
        fi
      fi
    fi

    # For multi-line @native defs: @native on its own line, def on next
    if echo "$line" | grep -qE '^\s+@native\s*$'; then
      if ! echo "$prev" | grep -qF '*/'; then
        echo "  MISSING DOC before @native at $FILE:$lineno"
        ERRORS=$((ERRORS + 1))
      fi
    fi

    # Update prev, skipping blank lines and annotations
    if [ -n "$(echo "$line" | tr -d ' \t')" ]; then
      prev="$line"
    fi
  done < "$FILE"

  if [ "$ERRORS" -gt 0 ]; then
    echo "FAIL: $ERRORS doc issue(s) in $FILE"
    TOTAL_ERRORS=$((TOTAL_ERRORS + ERRORS))
  else
    echo "OK: $FILE"
  fi
  echo ""
done

if [ "$TOTAL_ERRORS" -gt 0 ]; then
  echo "FAIL: $TOTAL_ERRORS total doc issues across all files"
  exit 1
fi

echo "OK: All public API methods have Scaladoc"