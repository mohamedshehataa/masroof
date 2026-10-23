#!/usr/bin/env bash
# make sbom - delegates to infra/scripts/sbom.sh once it exists (Phase 1+).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
delegate infra/scripts/sbom.sh "$@"
