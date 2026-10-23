#!/usr/bin/env bash
# make e2e - delegates to infra/scripts/e2e.sh once it exists (Phase 1+).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
delegate infra/scripts/e2e.sh "$@"
