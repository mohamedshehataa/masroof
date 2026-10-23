#!/usr/bin/env bash
# make deploy-staging|deploy-prod -> deploy.sh staging|prod <version>
# Refuses to run unless the /release skill marked the state release_active (guard-bash enforces too).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
env_name="${1:?staging|prod}"; ver="${2:-dev}"
active="$(bash scripts/sdlc/state.sh get release_active)"
if [ "$active" != "true" ] && [ -z "${MASROOF_RELEASE_ACTIVE:-}" ]; then
  echo "refusing: deploys run only through /release (state.release_active=true)." >&2; exit 3
fi
delegate infra/scripts/deploy.sh "$env_name" "$ver"
