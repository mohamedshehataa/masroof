#!/usr/bin/env bash
# Runs every hook test suite, prints a summary and writes a JUnit XML report
# (.claude/state/hook-tests.xml) that `make trace` reads. Exit 1 if any test fails.
set -u
TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$TESTS_DIR/../../.." && pwd)"
# shellcheck source=../../../scripts/env.sh
. "$REPO_DIR/scripts/env.sh"
export LC_ALL=C.UTF-8 2>/dev/null || true
command -v jq >/dev/null 2>&1 || { echo "hook tests need jq on PATH" >&2; exit 1; }

OUT_DIR="${HOOK_TEST_OUT_DIR:-$REPO_DIR/.claude/state}"
mkdir -p "$OUT_DIR"
export HOOK_TEST_RESULTS_TSV="$OUT_DIR/hook-tests.tsv"
: > "$HOOK_TEST_RESULTS_TSV"

total_fail=0
for t in "$TESTS_DIR"/test-*.sh; do
  echo "== $(basename "$t")"
  bash "$t" || total_fail=$((total_fail + 1))
done

# ---- JUnit XML for traceability ----
xml="$OUT_DIR/hook-tests.xml"
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

echo
echo "hook tests: $((tests - failures))/$tests passed; report: $xml"
[ "$total_fail" -eq 0 ] && [ "$failures" -eq 0 ]
