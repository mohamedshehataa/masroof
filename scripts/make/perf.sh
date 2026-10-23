#!/usr/bin/env bash
# make perf - delegates to infra/scripts/perf.sh once it exists (Phase 1+).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
delegate infra/scripts/perf.sh "$@"
