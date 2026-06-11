#!/usr/bin/env bash
set -euo pipefail

deployment_id="${1:-}"
if [ -z "$deployment_id" ]; then
  echo "Usage: $0 <deployment-id>" >&2
  exit 64
fi

if [ -z "${SONATYPE_USERNAME:-}" ] || [ -z "${SONATYPE_PASSWORD:-}" ]; then
  echo "SONATYPE_USERNAME and SONATYPE_PASSWORD are required." >&2
  exit 65
fi

auth_token="$(
  printf '%s:%s' "$SONATYPE_USERNAME" "$SONATYPE_PASSWORD" | base64 | tr -d '\n'
)"
status_url="https://central.sonatype.com/api/v1/publisher/status?id=${deployment_id}"

echo "Sonatype Central deployment status for ${deployment_id}:"
response="$(
  curl --fail-with-body --silent --show-error \
    --request POST \
    --header "Authorization: Bearer ${auth_token}" \
    "$status_url"
)"

if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$response" | jq .
else
  printf '%s\n' "$response"
fi
