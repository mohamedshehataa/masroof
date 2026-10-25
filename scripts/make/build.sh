#!/usr/bin/env bash
# make build-images|build-web|build-apk|build-apk-release  ->  build.sh images|web|apk-debug|apk-release [version]
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
. "$(dirname "${BASH_SOURCE[0]}")/gradlew-guard.sh"
what="${1:?images|web|apk-debug|apk-release}"; ver="${2:-dev}"
case "$what" in
  images) [ -f infra/scripts/build-images.sh ] || { echo "ERROR: infra/scripts/build-images.sh is missing" >&2; exit 1; }; bash infra/scripts/build-images.sh ;;
  web) [ -f web/package.json ] || { echo "skip: web not present"; exit 0; }; { { [ -f web/node_modules/.package-lock.json ] && [ ! web/package-lock.json -nt web/node_modules/.package-lock.json ]; } || (cd web && npm ci --no-audit --no-fund); } && (cd web && npm run -s build) ;;
  apk-debug) gradlew_state android; case $? in 2) echo "skip: android not present"; exit 0 ;; 1) exit 1 ;; esac; (cd android && ./gradlew --console=plain -q assembleDebug) ;;
  apk-release) gradlew_state android; case $? in 2) echo "skip: android not present"; exit 0 ;; 1) exit 1 ;; esac; delegate android/scripts/build-release.sh "$ver" ;;
  *) echo "unknown build target $what" >&2; exit 2 ;;
esac
