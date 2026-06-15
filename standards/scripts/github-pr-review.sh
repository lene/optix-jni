#!/bin/sh
# Post AI review findings to a GitHub PR as a bot review. Idempotent.
# Submits a COMMENT review (no approvals/rejections — advisory only).
# Updates the bot's existing review body if already present.
#
# Usage: github-pr-review.sh <findings-json>
#
# Env: GITHUB_TOKEN, GITHUB_REPOSITORY (owner/repo), GITHUB_PR_NUMBER,
#      GITHUB_SHA (for context)
# Requires: curl, jq
set -eu

FINDINGS_FILE="${1:-review-findings.json}"
[ -f "$FINDINGS_FILE" ] || { echo "error: findings file not found: $FINDINGS_FILE" >&2; exit 1; }
command -v jq   > /dev/null || { echo "error: jq required" >&2;   exit 1; }
command -v curl > /dev/null || { echo "error: curl required" >&2; exit 1; }

[ -n "${GITHUB_TOKEN:-}" ]       || { echo "error: GITHUB_TOKEN not set" >&2;       exit 1; }
[ -n "${GITHUB_REPOSITORY:-}" ]  || { echo "error: GITHUB_REPOSITORY not set" >&2;  exit 1; }
[ -n "${GITHUB_PR_NUMBER:-}" ]   || { echo "error: GITHUB_PR_NUMBER not set" >&2;   exit 1; }

BOT_MARKER="<!-- menger-ai-review-v1 -->"
GH_API="https://api.github.com"
REVIEWS_URL="${GH_API}/repos/${GITHUB_REPOSITORY}/pulls/${GITHUB_PR_NUMBER}/reviews"
AUTH_HEADER="Authorization: Bearer $GITHUB_TOKEN"

TOTAL=$(jq  '.stats.total'        "$FINDINGS_FILE")
AGREED=$(jq '.stats.agreed'       "$FINDINGS_FILE")
SINGLE=$(jq '.stats.single_model' "$FINDINGS_FILE")
CLAUDE_SUMMARY=$(jq  -r '.summaries.claude'   "$FINDINGS_FILE")
DS_SUMMARY=$(jq      -r '.summaries.deepseek' "$FINDINGS_FILE")
COMMIT_SHORT=$(printf '%.8s' "${GITHUB_SHA:-unknown}")

BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

cat >> "$BODY_FILE" << BODY_HEADER
${BOT_MARKER}
## 🤖 AI Code Review

_Claude + DeepSeek · commit \`${COMMIT_SHORT}\`_

**${TOTAL} findings** — ${AGREED} agreed (both models), ${SINGLE} single-model

BODY_HEADER

if [ "$TOTAL" -gt 0 ]; then
  printf '| File | Line | | Category | Finding | Models |\n' >> "$BODY_FILE"
  printf '|------|------|---|----------|---------|--------|\n' >> "$BODY_FILE"

  jq -r '.findings[] |
    [ .file, (.line|tostring), .severity, .category, .message,
      (.models | join(",")) ]
    | @tsv' "$FINDINGS_FILE" | while IFS="$(printf '\t')" read -r file line sev cat msg models; do
    case "$sev" in error) icon="🔴" ;; warning) icon="⚠️" ;; *) icon="ℹ️" ;; esac
    printf '| `%s` | %s | %s | %s | %s | %s |\n' \
      "$file" "$line" "$icon" "$cat" "$msg" "$models" >> "$BODY_FILE"
  done
  printf '\n' >> "$BODY_FILE"
fi

printf '### Summaries\n\n' >> "$BODY_FILE"
printf '**Claude:** %s\n\n' "$CLAUDE_SUMMARY" >> "$BODY_FILE"
printf '**DeepSeek:** %s\n' "$DS_SUMMARY" >> "$BODY_FILE"

REVIEW_BODY=$(cat "$BODY_FILE")
REVIEW_PAYLOAD=$(jq -n --arg body "$REVIEW_BODY" --arg sha "${GITHUB_SHA:-}" \
  '{body: $body, event: "COMMENT", commit_id: $sha}')

# Idempotency: find existing bot review, dismiss it before posting fresh
EXISTING_REVIEW_ID=$(curl -sf \
  -H "$AUTH_HEADER" \
  -H "Accept: application/vnd.github+json" \
  "${REVIEWS_URL}?per_page=100" \
  | jq -r --arg marker "$BOT_MARKER" \
      '[.[] | select(.body | contains($marker))] | first | .id // empty' \
  2>/dev/null || echo "")

if [ -n "$EXISTING_REVIEW_ID" ]; then
  echo "[github-review] Dismissing previous bot review #${EXISTING_REVIEW_ID}" >&2
  DISMISS_PAYLOAD='{"message":"Superseded by updated review"}'
  curl -sf -X PUT \
    -H "$AUTH_HEADER" \
    -H "Accept: application/vnd.github+json" \
    -H "content-type: application/json" \
    "${REVIEWS_URL}/${EXISTING_REVIEW_ID}/dismissals" \
    -d "$DISMISS_PAYLOAD" > /dev/null 2>&1 || true
fi

echo "[github-review] Posting review" >&2
curl -sf -X POST \
  -H "$AUTH_HEADER" \
  -H "Accept: application/vnd.github+json" \
  -H "content-type: application/json" \
  "${REVIEWS_URL}" \
  -d "$REVIEW_PAYLOAD" > /dev/null

echo "[github-review] Done. ${TOTAL} findings posted to PR #${GITHUB_PR_NUMBER}"
