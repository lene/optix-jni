#!/usr/bin/env bash

set -euo pipefail

release_mode=0
print_version=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --release)
      release_mode=1
      ;;
    --print-version)
      print_version=1
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

version="$(
  awk -F '"' '/^[[:space:]]*version[[:space:]]*:=[[:space:]]*"/ { print $2; exit }' build.sbt
)"

if [ -z "$version" ]; then
  echo "Could not extract version from build.sbt" >&2
  exit 1
fi

if [ "$print_version" -eq 1 ]; then
  printf '%s\n' "$version"
  exit 0
fi

readme_versions="$(
  grep -E 'optix-jni' README.md |
    grep -Eo '[0-9]+[.][0-9]+[.][0-9]+([-A-Za-z0-9.]+)?(-SNAPSHOT)?' |
    sort -u || true
)"

if [ -z "$readme_versions" ]; then
  echo "README.md does not contain an optix-jni dependency version" >&2
  exit 1
fi

status=0
while IFS= read -r readme_version; do
  if [ "$readme_version" != "$version" ]; then
    echo "README.md optix-jni version $readme_version does not match build.sbt $version" >&2
    status=1
  fi
done <<< "$readme_versions"

if [ "$release_mode" -eq 1 ]; then
  if [[ "$version" == *-SNAPSHOT ]]; then
    echo "Release version must not be a SNAPSHOT: $version" >&2
    status=1
  fi

  if git rev-parse -q --verify "refs/tags/$version" >/dev/null; then
    echo "Release tag already exists locally: $version" >&2
    status=1
  fi

  # Query remote tags directly so local tag refs are not overwritten or clobbered.
  if git ls-remote --exit-code --tags origin "refs/tags/$version" >/dev/null 2>&1; then
    echo "Release tag already exists on origin: $version" >&2
    status=1
  fi
fi

if [ "$status" -eq 0 ]; then
  if [ "$release_mode" -eq 1 ]; then
    echo "Version policy passed for release $version"
  else
    echo "Version policy passed for $version"
  fi
fi

exit "$status"
