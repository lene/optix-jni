#!/bin/sh
# Platform-agnostic AI code review core (Sprint 28.4).
# Calls Claude (Anthropic) and/or DeepSeek APIs; outputs consolidated findings JSON.
#
# Usage: review.sh --diff <path> --guidelines <path> [--arch-guidelines <path>]
#                  [--output <path>] [--max-diff-lines <N>]
#
# Env: ANTHROPIC_API_KEY, DEEPSEEK_API_KEY (either or both; missing → model skipped)
# Requires: curl, jq, python3
set -eu

DIFF_FILE=""
GUIDELINES_FILE=""
ARCH_GUIDELINES_FILE=""
OUTPUT_FILE="review-findings.json"
MAX_DIFF_LINES=4000

while [ $# -gt 0 ]; do
  case "$1" in
    --diff)              DIFF_FILE="$2";            shift 2 ;;
    --guidelines)        GUIDELINES_FILE="$2";       shift 2 ;;
    --arch-guidelines)   ARCH_GUIDELINES_FILE="$2";  shift 2 ;;
    --output)            OUTPUT_FILE="$2";            shift 2 ;;
    --max-diff-lines)    MAX_DIFF_LINES="$2";         shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

[ -n "$DIFF_FILE" ]       || { echo "error: --diff required" >&2;       exit 1; }
[ -n "$GUIDELINES_FILE" ] || { echo "error: --guidelines required" >&2; exit 1; }
[ -f "$DIFF_FILE" ]       || { echo "error: diff file not found: $DIFF_FILE" >&2; exit 1; }
[ -f "$GUIDELINES_FILE" ] || { echo "error: guidelines not found: $GUIDELINES_FILE" >&2; exit 1; }
command -v jq      > /dev/null || { echo "error: jq required" >&2;      exit 1; }
command -v python3 > /dev/null || { echo "error: python3 required" >&2; exit 1; }

# Skip gracefully if no API keys are configured — CI defers key setup,
# local runs without credentials should not fail the hook.
if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "review: skipped — set ANTHROPIC_API_KEY or DEEPSEEK_API_KEY to enable"
  printf '{"findings":[],"stats":{"total":0,"agreed":0,"single_model":0},"summaries":{"claude":"skipped (no key)","deepseek":"skipped (no key)"}}\n' > "$OUTPUT_FILE"
  exit 0
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Build combined guidelines
GUIDELINES=$(cat "$GUIDELINES_FILE")
if [ -n "$ARCH_GUIDELINES_FILE" ] && [ -f "$ARCH_GUIDELINES_FILE" ]; then
  GUIDELINES="${GUIDELINES}

## Additional Architecture Review Guidelines

$(cat "$ARCH_GUIDELINES_FILE")"
fi

# Truncate diff if too large
DIFF_LINE_COUNT=$(wc -l < "$DIFF_FILE")
if [ "$DIFF_LINE_COUNT" -gt "$MAX_DIFF_LINES" ]; then
  echo "Warning: diff is $DIFF_LINE_COUNT lines; truncating to $MAX_DIFF_LINES for review" >&2
  DIFF=$(head -n "$MAX_DIFF_LINES" "$DIFF_FILE")
  DIFF="${DIFF}
[... diff truncated: $DIFF_LINE_COUNT total lines, showing first $MAX_DIFF_LINES ...]"
else
  DIFF=$(cat "$DIFF_FILE")
fi

# --- Prompt construction ------------------------------------------------
SYSTEM_PROMPT='You are a senior code reviewer for a Scala 3 / CUDA / C++ ray tracer project. Review the provided diff and output ONLY valid JSON — no explanation, no markdown fences, no preamble. The response must be a single JSON object and nothing else.'

# Build user prompt by writing to a file to avoid shell quoting issues
PROMPT_FILE="$WORK/prompt.txt"
cat > "$PROMPT_FILE" << 'PROMPT_EOF'
Review the following code diff according to the guidelines below.
Apply each guideline and report findings that are not already caught by automated
tools (scalafix, scalafmt, WartRemover, sbt test).

PROMPT_EOF
echo "## Review Guidelines" >> "$PROMPT_FILE"
echo "" >> "$PROMPT_FILE"
printf '%s\n' "$GUIDELINES" >> "$PROMPT_FILE"
cat >> "$PROMPT_FILE" << 'PROMPT_EOF'

## Diff

PROMPT_EOF
printf '```diff\n' >> "$PROMPT_FILE"
printf '%s\n' "$DIFF" >> "$PROMPT_FILE"
printf '```\n' >> "$PROMPT_FILE"
cat >> "$PROMPT_FILE" << 'PROMPT_EOF'

Output this exact JSON structure (and nothing else):
{
  "findings": [
    {
      "file": "path/relative/to/repo/root",
      "line": 0,
      "category": "correctness",
      "severity": "warning",
      "message": "concise description"
    }
  ],
  "summary": "1-3 sentence overall assessment"
}

Where:
- "file": path as it appears in the diff header (relative to repo root)
- "line": line number in the new file (use 0 for file-level findings)
- "category": one of: correctness, architecture, performance, code-quality, security
- "severity": one of: error, warning, info
- "findings" may be an empty array if there are no issues worth flagging
PROMPT_EOF

USER_PROMPT=$(cat "$PROMPT_FILE")

# --- Claude API call -----------------------------------------------------
CLAUDE_OUT="$WORK/claude.json"
if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  echo "[review] Calling Claude API (claude-sonnet-4-6)..." >&2
  REQUEST=$(jq -n \
    --arg sys  "$SYSTEM_PROMPT" \
    --arg user "$USER_PROMPT" \
    '{
      model: "claude-sonnet-4-6",
      max_tokens: 4096,
      system: $sys,
      messages: [{"role": "user", "content": $user}]
    }')
  HTTP_CODE=$(curl -s -o "$WORK/claude_raw.json" -w "%{http_code}" \
    -X POST "https://api.anthropic.com/v1/messages" \
    -H "x-api-key: $ANTHROPIC_API_KEY" \
    -H "anthropic-version: 2023-06-01" \
    -H "content-type: application/json" \
    -d "$REQUEST" 2>/dev/null || echo "000")
  if [ "$HTTP_CODE" = "200" ]; then
    TEXT=$(jq -r '.content[0].text // empty' "$WORK/claude_raw.json" 2>/dev/null || echo "")
    if [ -n "$TEXT" ]; then
      printf '%s' "$TEXT" > "$CLAUDE_OUT"
    else
      echo "Warning: Claude API returned HTTP 200 but empty text content; raw response:" >&2
      cat "$WORK/claude_raw.json" >&2
      echo '{"findings":[],"summary":"Claude returned empty response"}' > "$CLAUDE_OUT"
    fi
  else
    echo "Warning: Claude API returned HTTP $HTTP_CODE; raw response:" >&2
    cat "$WORK/claude_raw.json" >&2
    echo '{"findings":[],"summary":"Claude API error (HTTP '"$HTTP_CODE"')"}' > "$CLAUDE_OUT"
  fi
else
  echo "[review] ANTHROPIC_API_KEY not set; skipping Claude" >&2
  echo '{"findings":[],"summary":"Claude skipped (no API key)"}' > "$CLAUDE_OUT"
fi

# --- DeepSeek API call ---------------------------------------------------
DEEPSEEK_OUT="$WORK/deepseek.json"
if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[review] Calling DeepSeek API (deepseek-chat)..." >&2
  REQUEST=$(jq -n \
    --arg sys  "$SYSTEM_PROMPT" \
    --arg user "$USER_PROMPT" \
    '{
      model: "deepseek-chat",
      max_tokens: 4096,
      response_format: {"type": "json_object"},
      messages: [
        {"role": "system", "content": $sys},
        {"role": "user",   "content": $user}
      ]
    }')
  HTTP_CODE=$(curl -s -o "$WORK/deepseek_raw.json" -w "%{http_code}" \
    -X POST "https://api.deepseek.com/v1/chat/completions" \
    -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
    -H "content-type: application/json" \
    -d "$REQUEST" 2>/dev/null || echo "000")
  if [ "$HTTP_CODE" = "200" ]; then
    TEXT=$(jq -r '.choices[0].message.content // empty' "$WORK/deepseek_raw.json" 2>/dev/null || echo "")
    if [ -n "$TEXT" ]; then
      printf '%s' "$TEXT" > "$DEEPSEEK_OUT"
    else
      echo "Warning: DeepSeek API returned HTTP 200 but empty content; raw response:" >&2
      cat "$WORK/deepseek_raw.json" >&2
      echo '{"findings":[],"summary":"DeepSeek returned empty response"}' > "$DEEPSEEK_OUT"
    fi
  else
    echo "Warning: DeepSeek API returned HTTP $HTTP_CODE; raw response:" >&2
    cat "$WORK/deepseek_raw.json" >&2
    echo '{"findings":[],"summary":"DeepSeek API error (HTTP '"$HTTP_CODE"')"}' > "$DEEPSEEK_OUT"
  fi
else
  echo "[review] DEEPSEEK_API_KEY not set; skipping DeepSeek" >&2
  echo '{"findings":[],"summary":"DeepSeek skipped (no API key)"}' > "$DEEPSEEK_OUT"
fi

# --- Consolidate ---------------------------------------------------------
python3 - "$CLAUDE_OUT" "$DEEPSEEK_OUT" "$OUTPUT_FILE" << 'PYEOF'
import json
import sys

def load_safe(path):
    try:
        with open(path) as f:
            data = json.load(f)
        if not isinstance(data.get("findings"), list):
            return {"findings": [], "summary": "parse error: no findings array"}
        return data
    except Exception as e:
        return {"findings": [], "summary": f"load error: {e}"}

def normalize(f):
    return {
        "file":     str(f.get("file", "")).strip(),
        "line":     int(f.get("line", 0) or 0),
        "category": str(f.get("category", "code-quality")).strip(),
        "severity": str(f.get("severity", "info")).strip(),
        "message":  str(f.get("message", "")).strip(),
    }

AGREE_WINDOW = 10  # lines within which same file+category → "agreed"

claude_data   = load_safe(sys.argv[1])
deepseek_data = load_safe(sys.argv[2])
output_path   = sys.argv[3]

claude_findings   = [normalize(f) for f in claude_data.get("findings",   [])]
deepseek_findings = [normalize(f) for f in deepseek_data.get("findings", [])]

used_ds = set()
merged  = []

for cf in claude_findings:
    agreed = False
    for i, df in enumerate(deepseek_findings):
        if i in used_ds:
            continue
        if (cf["file"] == df["file"]
                and cf["category"] == df["category"]
                and abs(cf["line"] - df["line"]) <= AGREE_WINDOW):
            merged.append({**cf, "models": ["claude", "deepseek"], "agreement": "agreed"})
            used_ds.add(i)
            agreed = True
            break
    if not agreed:
        merged.append({**cf, "models": ["claude"], "agreement": "single-model"})

for i, df in enumerate(deepseek_findings):
    if i not in used_ds:
        merged.append({**df, "models": ["deepseek"], "agreement": "single-model"})

# Sort: errors first; agreed before single-model within same severity
sev_order = {"error": 0, "warning": 1, "info": 2}
agr_order = {"agreed": 0, "single-model": 1}
merged.sort(key=lambda f: (sev_order.get(f["severity"], 9), agr_order.get(f["agreement"], 9)))

agreed_count = sum(1 for f in merged if f["agreement"] == "agreed")
result = {
    "findings": merged,
    "summaries": {
        "claude":   claude_data.get("summary",   ""),
        "deepseek": deepseek_data.get("summary", ""),
    },
    "stats": {
        "total":        len(merged),
        "agreed":       agreed_count,
        "single_model": len(merged) - agreed_count,
    },
}

with open(output_path, "w") as f:
    json.dump(result, f, indent=2)

s = result["stats"]
print(f"[review] {s['total']} findings: {s['agreed']} agreed, {s['single_model']} single-model")
PYEOF
