#!/usr/bin/env bash
# make backend-integration (REQ-OPS-060): backend Testcontainers integrationTest + ArchUnit archTest.
# Fails when Gradle fails, or when either suite wrote no JUnit XML or ran zero tests
# (e.g. Docker unusable and every test filtered out): never green with 0 tests executed.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR/backend" || { echo "backend-integration: backend/ not present" >&2; exit 1; }
[ -x ./gradlew ] || { echo "backend-integration: backend/gradlew not present" >&2; exit 1; }

suites="integrationTest archTest"
for s in $suites; do rm -rf "build/test-results/$s"; done # no stale results

# shellcheck disable=SC2086
./gradlew --console=plain -q $suites || { echo "backend-integration: gradle failed" >&2; exit 1; }

rc=0
for s in $suites; do
  total=0
  for f in "build/test-results/$s"/TEST-*.xml; do
    [ -f "$f" ] || continue
    n=$(sed -n 's/.*<testsuite [^>]*tests="\([0-9][0-9]*\)".*/\1/p' "$f" | head -n 1)
    total=$((total + ${n:-0}))
  done
  echo "backend-integration: $s executed $total test(s)"
  if [ "$total" -le 0 ]; then
    echo "ERROR: $s executed no tests (no results under backend/build/test-results/$s/)" >&2
    rc=1
  fi
done
exit "$rc"
