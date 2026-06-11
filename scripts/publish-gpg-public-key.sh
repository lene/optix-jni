#!/usr/bin/env bash
set -euo pipefail

keyservers=(
  "hkps://keyserver.ubuntu.com"
  "hkps://keys.openpgp.org"
  "hkp://pgp.mit.edu"
)

mapfile -t fingerprints < <(
  gpg --batch --list-secret-keys --with-colons --fingerprint |
    awk -F: '
      /^sec/ { want_fingerprint = 1; next }
      /^fpr/ && want_fingerprint { print $10; want_fingerprint = 0 }
    '
)

if [ "${#fingerprints[@]}" -eq 0 ]; then
  echo "No secret GPG keys are available to publish." >&2
  exit 65
fi

echo "Publishing GPG public key fingerprints:"
printf '  %s\n' "${fingerprints[@]}"

failed=0
for fingerprint in "${fingerprints[@]}"; do
  successful_servers=0

  for keyserver in "${keyservers[@]}"; do
    echo "Sending ${fingerprint} to ${keyserver}"
    if gpg --batch --keyserver "${keyserver}" --send-keys "${fingerprint}"; then
      successful_servers=$((successful_servers + 1))
    else
      echo "WARNING: failed to send ${fingerprint} to ${keyserver}" >&2
    fi
  done

  if [ "${successful_servers}" -eq 0 ]; then
    echo "ERROR: failed to publish ${fingerprint} to any supported keyserver" >&2
    failed=1
  fi
done

exit "${failed}"
