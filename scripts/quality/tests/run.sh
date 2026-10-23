#!/usr/bin/env bash
# Runs every scripts/quality test: test_*.py via unittest (through junit_runner.py) and every test-*.sh.
# Writes scripts/quality/tests/results.xml (JUnit; read by `make trace`). Exit 1 if anything fails.
# A shell test may record granular cases by appending "suite<TAB>ok|fail<TAB>name<TAB>detail" lines to
# $QUALITY_TEST_RESULTS_TSV; otherwise the script's exit code becomes one case.
set -u
TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$TESTS_DIR/../../.." && pwd)"
# shellcheck source=../../env.sh
. "$REPO_DIR/scripts/env.sh"
export LC_ALL=C.UTF-8 2>/dev/null || true
export PYTHONIOENCODING=utf-8

OUT="${QUALITY_TEST_OUT:-$TESTS_DIR/results.xml}"
TSV="$(mktemp)"
export QUALITY_TEST_RESULTS_TSV="$TSV"
: > "$TSV"
fail=0

for t in "$TESTS_DIR"/test-*.sh; do
  [ -e "$t" ] || continue
  name="$(basename "$t" .sh)"
  before="$(wc -l < "$TSV" | tr -d ' ')"
  echo "== $name"
  if out="$(bash "$t" 2>&1)"; then rc=0; else rc=$?; fi
  printf '%s\n' "$out"
  after="$(wc -l < "$TSV" | tr -d ' ')"
  if [ "$after" -eq "$before" ]; then
    if [ "$rc" -eq 0 ]; then
      printf '%s\tok\t%s\t\n' "$name" "$name" >> "$TSV"
    else
      printf '%s\tfail\t%s\t%s\n' "$name" "$name" "exit $rc: $(printf '%s' "$out" | tail -n 5 | tr '\n\t' '  ')" >> "$TSV"
    fi
  fi
  [ "$rc" -eq 0 ] || fail=1
done

# $PYTHON may be "py -3" (two words), so it is intentionally unquoted.
# shellcheck disable=SC2086
$PYTHON "$TESTS_DIR/junit_runner.py" --tests-dir "$TESTS_DIR" --out "$OUT" --shell-tsv "$TSV" || fail=1
rm -f "$TSV"
exit "$fail"
