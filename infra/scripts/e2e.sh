#!/usr/bin/env bash
# make e2e: runs every infra/tests/*.sh (STORY-142 adds the Playwright step).
set -uo pipefail
shopt -s nullglob
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1
rc=0
for t in infra/tests/*.sh; do
  echo "===== e2e: $t"
  bash "$t" || { echo "FAIL $t"; rc=1; }
done
echo "===== e2e: web Playwright against http://localhost (make up)"
(cd web && E2E_BASE_URL=http://localhost npm run e2e) || { echo "FAIL web npm run e2e"; rc=1; }
exit $rc
