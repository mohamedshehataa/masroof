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

# The shell suites run in parallel (STORY-001 loop 3, check-fast budget), each with its own TSV and log;
# results are merged and printed in suite order.
PAR="$(mktemp -d)"
suites=()
for t in "$TESTS_DIR"/test-*.sh; do [ -e "$t" ] && suites+=("$t"); done
for k in "${!suites[@]}"; do
  : > "$PAR/$k.tsv"
  { QUALITY_TEST_RESULTS_TSV="$PAR/$k.tsv" bash "${suites[$k]}" < /dev/null > "$PAR/$k.log" 2>&1; printf '%s' "$?" > "$PAR/$k.rc"; } &
done
wait
for k in "${!suites[@]}"; do
  name="$(basename "${suites[$k]}" .sh)"
  rc="$(cat "$PAR/$k.rc" 2>/dev/null || echo 1)"
  echo "== $name"
  cat "$PAR/$k.log"
  if [ -s "$PAR/$k.tsv" ]; then cat "$PAR/$k.tsv" >> "$TSV"
  elif [ "$rc" -eq 0 ]; then printf '%s\tok\t%s\t\n' "$name" "$name" >> "$TSV"
  else printf '%s\tfail\t%s\t%s\n' "$name" "$name" "exit $rc: $(tail -n 5 "$PAR/$k.log" | tr '\n\t' '  ')" >> "$TSV"
  fi
  [ "$rc" -eq 0 ] || fail=1
done
rm -rf "$PAR"

# $PYTHON may be "py -3" (two words), so it is intentionally unquoted.
# shellcheck disable=SC2086
$PYTHON "$TESTS_DIR/junit_runner.py" --tests-dir "$TESTS_DIR" --out "$OUT" --shell-tsv "$TSV" || fail=1
rm -f "$TSV"
exit "$fail"
