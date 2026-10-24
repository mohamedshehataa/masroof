#!/usr/bin/env bash
# Runs every hook test suite, prints a summary and writes a JUnit XML report
# (.claude/state/hook-tests.xml) that `make trace` reads. Exit 1 if any test fails.
# The suites run in parallel (STORY-001 loop 3: check-fast MODULE=sdlc must stay < 3 min): each has its
# own temp dir, isolated state and its own result TSV and log; the logs are printed in suite order.
# HOOK_TEST_JOBS caps the parallelism (default: all suites at once).
set -u
TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$TESTS_DIR/../../.." && pwd)"
# shellcheck source=../../../scripts/env.sh
. "$REPO_DIR/scripts/env.sh"
export LC_ALL=C.UTF-8 2>/dev/null || true
command -v jq >/dev/null 2>&1 || { echo "hook tests need jq on PATH" >&2; exit 1; }

OUT_DIR="${HOOK_TEST_OUT_DIR:-$REPO_DIR/.claude/state}"
mkdir -p "$OUT_DIR"
# Results go to a per-run temp dir, so two concurrent runs (e.g. a stop-gate check-fast in another
# session) never mix their rows; the final TSV/XML are copied into $OUT_DIR at the end.
RUN_DIR="$(mktemp -d)"
trap 'rm -rf "$RUN_DIR"' EXIT
JOBS="${HOOK_TEST_JOBS:-0}"

suites=("$TESTS_DIR"/test-*.sh)
running=0
for k in "${!suites[@]}"; do
  if [ "$JOBS" -gt 0 ] && [ "$running" -ge "$JOBS" ]; then wait -n; running=$((running - 1)); fi
  { HOOK_TEST_RESULTS_TSV="$RUN_DIR/$k.tsv" bash "${suites[$k]}" < /dev/null > "$RUN_DIR/$k.log" 2>&1; printf '%s' "$?" > "$RUN_DIR/$k.rc"; } &
  running=$((running + 1))
done
wait
total_fail=0
for k in "${!suites[@]}"; do
  echo "== $(basename "${suites[$k]}")"
  cat "$RUN_DIR/$k.log"
  [ "$(cat "$RUN_DIR/$k.rc" 2>/dev/null)" = 0 ] || total_fail=$((total_fail + 1))
done
export HOOK_TEST_RESULTS_TSV="$RUN_DIR/hook-tests.tsv"
: > "$HOOK_TEST_RESULTS_TSV"
for k in "${!suites[@]}"; do [ -f "$RUN_DIR/$k.tsv" ] && cat "$RUN_DIR/$k.tsv" >> "$HOOK_TEST_RESULTS_TSV"; done

# ---- JUnit XML for traceability ----
xml="$RUN_DIR/hook-tests.xml"
tests=$(wc -l < "$HOOK_TEST_RESULTS_TSV" | tr -d ' ')
failures=$(awk -F'\t' '$2=="fail"' "$HOOK_TEST_RESULTS_TSV" | wc -l | tr -d ' ')
{
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuites name="hook-tests" tests="%s" failures="%s">\n' "$tests" "$failures"
  for suite in $(cut -f1 "$HOOK_TEST_RESULTS_TSV" | sort -u); do
    st=$(awk -F'\t' -v s="$suite" '$1==s' "$HOOK_TEST_RESULTS_TSV" | wc -l | tr -d ' ')
    sf=$(awk -F'\t' -v s="$suite" '$1==s && $2=="fail"' "$HOOK_TEST_RESULTS_TSV" | wc -l | tr -d ' ')
    printf '  <testsuite name="%s" tests="%s" failures="%s">\n' "$suite" "$st" "$sf"
    awk -F'\t' -v s="$suite" '$1==s {
      name=$3; gsub(/&/,"\\&amp;",name); gsub(/</,"\\&lt;",name); gsub(/"/,"\\&quot;",name);
      det=$4; gsub(/&/,"\\&amp;",det); gsub(/</,"\\&lt;",det);
      if ($2=="ok") printf "    <testcase classname=\"%s\" name=\"%s\"/>\n", s, name;
      else printf "    <testcase classname=\"%s\" name=\"%s\"><failure message=\"failed\">%s</failure></testcase>\n", s, name, det;
    }' "$HOOK_TEST_RESULTS_TSV"
    printf '  </testsuite>\n'
  done
  printf '</testsuites>\n'
} > "$xml"
# copy then rename, so a reader never sees a half-written report
cp "$xml" "$OUT_DIR/hook-tests.xml.$$" && mv -f "$OUT_DIR/hook-tests.xml.$$" "$OUT_DIR/hook-tests.xml"
cp "$HOOK_TEST_RESULTS_TSV" "$OUT_DIR/hook-tests.tsv.$$" && mv -f "$OUT_DIR/hook-tests.tsv.$$" "$OUT_DIR/hook-tests.tsv"

echo
echo "hook tests: $((tests - failures))/$tests passed; report: $OUT_DIR/hook-tests.xml"
[ "$total_fail" -eq 0 ] && [ "$failures" -eq 0 ]
