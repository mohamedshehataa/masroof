#!/usr/bin/env bash
# make check-full: check + e2e + screenshot diffs + mutation + full eval + security + perf + migration tests.
# Nightly and phase-gate only. Each stage is skipped (not failed) when its module does not exist yet.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
export LC_ALL=C.UTF-8
fail=0
run() { local d="$1"; shift; printf '\n### %s\n' "$d"; if "$@"; then printf 'OK   %s\n' "$d"; else printf 'FAIL %s\n' "$d"; fail=1; fi; }

run "check" bash scripts/make/check.sh
run "security" bash scripts/make/security.sh
run "eval (full)" bash scripts/make/eval.sh full
[ -x backend/gradlew ] && run "mutation (PIT)" bash scripts/make/module.sh backend mutation
[ -x backend/gradlew ] && run "coverage gate incl. mutation" "$PYTHON" scripts/quality/coverage-gate.py --require-mutation
[ -x backend/gradlew ] && run "migration tests (empty + previous snapshot)" bash scripts/make/module.sh backend migrationTest
if [ -f web/package.json ] || [ -x android/gradlew ]; then run "e2e" bash scripts/make/e2e.sh; fi
if [ -f web/package.json ] || [ -x backend/gradlew ]; then run "perf" bash scripts/make/perf.sh; fi
run "metrics" "$PYTHON" scripts/quality/sdlc-metrics.py --out docs/SDLC_METRICS.md
[ "$fail" -eq 0 ] && { echo; echo "check-full GREEN"; exit 0; } || { echo; echo "check-full RED"; exit 1; }
