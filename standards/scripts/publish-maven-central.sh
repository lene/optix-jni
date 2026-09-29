#!/usr/bin/env bash
# Publishes the current sbt project to Maven Central, idempotently, with presence on Maven
# Central (repo1.maven.org) as the source of truth.
#
# Why: sbt-sonatype's `sonatypeBundleRelease` uploads the bundle, then polls Central's status
# API and fails the whole task on a transient API error (a 502 or a read timeout) even though
# the upload succeeded and the artifact goes on to publish. That red-failed optix-jni 0.1.17,
# menger-common 0.1.6 and optix-jni 0.4.2, and skipped each release's GitHub Release step.
# Re-running the job was unsafe, because it uploaded the same (permanent) version again.
#
#   - already on Central        -> no upload, exit 0 (so re-running a job is safe)
#   - upload succeeds           -> exit 0
#   - upload happened (a deployment id was logged) but sbt failed afterwards
#                               -> never upload again; wait for the artifact on Central
#   - Central rejected the deployment (state FAILED) -> exit 1 at once
#   - no deployment was created -> retry the upload, up to PUBLISH_UPLOAD_ATTEMPTS times
#
# Usage: publish-maven-central.sh <group/artifact path> <version> <sbt task>...
#   e.g.  publish-maven-central.sh io/github/lene/optix-jni 0.4.2 publishSigned sonatypeBundleRelease
set -euo pipefail

ARTIFACT_PATH="$1"
VERSION="$2"
shift 2
ARTIFACT="${ARTIFACT_PATH##*/}"
POM_URL="https://repo1.maven.org/maven2/${ARTIFACT_PATH}/${VERSION}/${ARTIFACT}-${VERSION}.pom"

UPLOAD_ATTEMPTS="${PUBLISH_UPLOAD_ATTEMPTS:-3}"
RETRY_SECONDS="${PUBLISH_RETRY_SECONDS:-30}"
POLL_SECONDS="${PUBLISH_POLL_SECONDS:-30}"
# 120 polls x 30 s = 60 min: Central usually publishes within 10-30 min of the upload.
WAIT_POLLS="${PUBLISH_WAIT_POLLS:-120}"

on_central() { curl -sfI -o /dev/null "$POM_URL"; }

if on_central; then
    echo "${ARTIFACT} ${VERSION} is already on Maven Central: nothing to upload"
    exit 0
fi

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT
uploaded=false
for attempt in $(seq 1 "$UPLOAD_ATTEMPTS"); do
    echo "Upload attempt ${attempt}/${UPLOAD_ATTEMPTS}: sbt $*"
    if sbt "$@" 2>&1 | tee "$LOG"; then
        echo "${ARTIFACT} ${VERSION} published"
        exit 0
    fi
    if grep -q 'Current deployment state: FAILED' "$LOG"; then
        echo "Maven Central rejected the deployment of ${ARTIFACT} ${VERSION}" >&2
        exit 1
    fi
    if grep -q 'deployment id:' "$LOG"; then
        uploaded=true
        break
    fi
    echo "No deployment was created; retrying in ${RETRY_SECONDS} s"
    sleep "$RETRY_SECONDS"
done

if [[ "$uploaded" != true ]]; then
    echo "Upload of ${ARTIFACT} ${VERSION} failed ${UPLOAD_ATTEMPTS} times" >&2
    exit 1
fi

echo "The bundle was uploaded, but sbt failed afterwards (status check): not uploading again."
echo "Waiting for ${POM_URL}"
for _ in $(seq 1 "$WAIT_POLLS"); do
    if on_central; then
        echo "${ARTIFACT} ${VERSION} is on Maven Central"
        exit 0
    fi
    sleep "$POLL_SECONDS"
done
echo "${ARTIFACT} ${VERSION} did not appear on Maven Central after ${WAIT_POLLS} polls" >&2
exit 1
