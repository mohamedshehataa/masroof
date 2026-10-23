#!/usr/bin/env bash
# make build-images|build-web|build-apk|build-apk-release  ->  build.sh images|web|apk-debug|apk-release [version]
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
what="${1:?images|web|apk-debug|apk-release}"; ver="${2:-dev}"
case "$what" in
  images) delegate infra/scripts/build-images.sh "$ver" ;;
  web) [ -f web/package.json ] || { echo "skip: web not present"; exit 0; }; (cd web && npm run -s build) ;;
  apk-debug) [ -x android/gradlew ] || { echo "skip: android not present"; exit 0; }; (cd android && ./gradlew --console=plain -q assembleDebug) ;;
  apk-release) [ -x android/gradlew ] || { echo "skip: android not present"; exit 0; }; delegate android/scripts/build-release.sh "$ver" ;;
  *) echo "unknown build target $what" >&2; exit 2 ;;
esac
