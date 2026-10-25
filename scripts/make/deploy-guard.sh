#!/usr/bin/env bash
# deploy-guard.sh <script> [args...]: runs a server-touching script only while /release is armed (release_active).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
active="$(bash scripts/sdlc/state.sh get release_active)"
if [ "$active" != "true" ] && [ -z "${MASROOF_RELEASE_ACTIVE:-}" ]; then
  echo "refusing: server changes run only through /release (state.release_active=true)." >&2; exit 3
fi
delegate "$@"
