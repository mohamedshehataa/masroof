#!/usr/bin/env bash
# make check: everything in check-fast for all present modules + integration tests + contract diff
# + coverage gates + traceability + test integrity. Budget < 15 min.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
export LC_ALL=C.UTF-8
fail=0
run() { local d="$1"; shift; printf '\n### %s\n' "$d"; if "$@"; then printf 'OK   %s\n' "$d"; else printf 'FAIL %s\n' "$d"; fail=1; fi; }

mods="sdlc"; [ -x backend/gradlew ] && mods="$mods backend"; [ -f web/package.json ] && mods="$mods web"; [ -x android/gradlew ] && mods="$mods android"
# shellcheck disable=SC2086
run "check-fast [$mods]" bash scripts/make/check-fast.sh $mods
[ -x backend/gradlew ] && run "backend integration tests (Testcontainers)" bash -c 'cd backend && ./gradlew --console=plain -q integrationTest'
[ -x backend/gradlew ] && run "backend architecture tests (ArchUnit)" bash -c 'cd backend && ./gradlew --console=plain -q archTest'
[ -f web/package.json ] && run "web unit coverage" bash -c 'cd web && npm run -s test:coverage'
[ -f contracts/openapi.yaml ] && run "contract diff vs ${BASE:-main}" bash scripts/quality/oasdiff.sh "${BASE:-main}"
[ -f scripts/quality/coverage-gate.sh ] && run "coverage gates" bash scripts/quality/coverage-gate.sh
[ -f scripts/quality/traceability.py ] && run "traceability" "$PYTHON" scripts/quality/traceability.py --out docs/TRACEABILITY.md
if [ -f scripts/quality/test-integrity-check.sh ] && git rev-parse --verify -q "${BASE:-main}" >/dev/null 2>&1; then
  run "test integrity vs ${BASE:-main}" bash scripts/quality/test-integrity-check.sh "${BASE:-main}"
fi
[ "$fail" -eq 0 ] && { echo; echo "check GREEN"; exit 0; } || { echo; echo "check RED"; exit 1; }
