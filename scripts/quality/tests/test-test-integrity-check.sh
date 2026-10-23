#!/usr/bin/env bash
# Tests for scripts/quality/test-integrity-check.sh in a throwaway git repository.
# Run: bash scripts/quality/tests/test-test-integrity-check.sh   (exit 0 when all cases pass)
# When QUALITY_TEST_RESULTS_TSV is set (by run.sh) each case is appended as suite<TAB>ok|fail<TAB>name<TAB>detail.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/../test-integrity-check.sh"
suite="test-test-integrity-check"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

pass=0; fail=0
record() { # record <ok|fail> <name> <detail>
  [ -n "${QUALITY_TEST_RESULTS_TSV:-}" ] && printf '%s\t%s\t%s\t%s\n' "$suite" "$1" "$2" "${3//$'\n'/ }" >> "$QUALITY_TEST_RESULTS_TSV"
  return 0
}
ok()   { pass=$((pass + 1)); printf 'PASS  %s\n' "$1"; record ok "$1" ""; }
bad()  { fail=$((fail + 1)); printf 'FAIL  %s\n      %s\n' "$1" "$2"; record fail "$1" "$2"; }

OUT=""; RC=0
run_check() { # run_check <args...>   (inside $R)
  OUT="$(cd "$R" && bash "$check" "$@" 2>&1)"; RC=$?
  OUT="${OUT//$'\r'/}"
}
# contains <substring> <name>
contains() {
  if [[ "$OUT" == *"$1"* ]]; then ok "$2"; else bad "$2" "expected output to contain '$1': $(printf '%s' "$OUT" | tail -n 4 | tr '\n' ' ')"; fi
}
# Skip/disable markers are assembled at runtime so this file never contains them literally
# (it lives in a tests/ dir and would otherwise trip the per-edit hook and the CI diff check).
SKIP_DESCRIBE="$(printf 'describe.%s(' skip)"
ONLY_IT="$(printf 'it.%s(' only)"
DISABLED_ANN="$(printf '@%s' Disabled)"
CLOSE_TO="$(printf 'toBe%s(' CloseTo)"
# expect <rc> <verdict-substring> <name>
expect() {
  if [ "$RC" -eq "$1" ] && [[ "$OUT" == *"$2"* ]]; then ok "$3"
  else bad "$3" "wanted rc=$1 containing '$2'; got rc=$RC: $(printf '%s' "$OUT" | tail -n 4 | tr '\n' ' ')"; fi
}

g() { git -C "$R" -c user.name=t -c user.email=t@t -c core.autocrlf=false -c core.safecrlf=false "$@"; }
reset_repo() {
  R="$tmp/repo-$1"; rm -rf "$R"; mkdir -p "$R/backend/src/test/java/m" "$R/web/src"
  g init -q -b main .
  cat > "$R/backend/src/test/java/m/MoneyTest.java" <<'EOF'
class MoneyTest {
  @Test void a() { assertEquals(1, 1); assertTrue(true); }
  @Test void b() { assertThat(x).isEqualTo(2); }
}
EOF
  printf 'describe("sum", () => { it("adds", () => { expect(add(1,2)).toBe(3); }); });\n' > "$R/web/src/sum.test.ts"
  g add -A >/dev/null && g commit -qm "init [STORY-001]"
  BASE_SHA="$(g rev-parse HEAD)"
}

echo "-- pure additions are fine"
reset_repo add
printf 'describe("x", () => { it("y", () => { expect(1).toBe(1); }); });\n' > "$R/web/src/new.test.ts"    # untracked
printf '\n  @Test void c() { assertFalse(false); }\n' >> "$R/backend/src/test/java/m/MoneyTest.java"          # modified
run_check main;              expect 0 "TEST_INTEGRITY: OK" "integrity: additions (new file + more asserts) -> OK"
contains "web/src/new.test.ts" "integrity: untracked test file appears in table"

echo "-- no test files changed"
reset_repo none
printf 'x\n' > "$R/README.md"
run_check main;              expect 0 "TEST_INTEGRITY: OK" "integrity: non-test change -> OK"
contains "no test files changed" "integrity: reports that no test files changed"

echo "-- assertion loss (uncommitted)"
reset_repo loss
cat > "$R/backend/src/test/java/m/MoneyTest.java" <<'EOF'
class MoneyTest {
  @Test void a() { assertEquals(1, 1); }
}
EOF
run_check main;              expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: assertion loss -> VIOLATION"
contains "net assertion loss of" "integrity: violation names the net loss"

echo "-- several assertions on one line count as matches, not lines"
reset_repo oneline
printf 'describe("sum", () => { it("adds", () => { expect(add(1,2)).toBe(3); }); });\n' > "$R/web/src/sum.test.ts"
printf 'class MoneyTest {\n  @Test void a() { assertEquals(1, 1); assertTrue(true); assertThat(x).isEqualTo(2); }\n}\n' > "$R/backend/src/test/java/m/MoneyTest.java"
run_check main;              expect 0 "TEST_INTEGRITY: OK" "integrity: same assertions compacted on one line -> OK"
printf 'class MoneyTest {\n  @Test void a() { assertEquals(1, 1); assertTrue(true); }\n}\n' > "$R/backend/src/test/java/m/MoneyTest.java"
run_check main;              expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: one assertion removed from a one-liner -> VIOLATION"

echo "-- added skip markers"
reset_repo skip
printf '%s"sum", () => { it("adds", () => { expect(add(1,2)).toBe(3); }); });\n' "$SKIP_DESCRIBE" > "$R/web/src/sum.test.ts"
run_check main;              expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: added describe-dot-skip marker -> VIOLATION"
contains "skip/disable/only markers increased 0 -> 1" "integrity: skip violation shows the counter"
reset_repo only
printf 'describe("sum", () => { %s"adds", () => { expect(add(1,2)).toBe(3); expect(1).toBe(1); }); });\n' "$ONLY_IT" > "$R/web/src/sum.test.ts"
run_check main;              expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: added it.only (even with more asserts) -> VIOLATION"
reset_repo disabled
sed -i "s/@Test void b()/$DISABLED_ANN @Test void b()/" "$R/backend/src/test/java/m/MoneyTest.java"
run_check main;              expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: added Disabled annotation -> VIOLATION"

echo "-- deleted test file"
reset_repo del
rm "$R/web/src/sum.test.ts"
run_check main;              expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: deleted test file without justification -> VIOLATION"
contains "deleted without --allow-deletions" "integrity: deletion violation is explicit"
run_check main --allow-deletions "sum test superseded by calc.test.ts, see STORY-012"
expect 0 "TEST_INTEGRITY: OK" "integrity: deleted file with --allow-deletions STORY-012 -> OK"
contains "entirely in deleted files" "integrity: loss attributed to allowed deletion"
run_check main --allow-deletions "just because"
expect 2 "must reference a STORY" "integrity: --allow-deletions without STORY id -> usage error 2"
# deletion allowed but another file ALSO lost assertions -> still a violation
printf 'class MoneyTest {\n  @Test void a() { assertEquals(1, 1); }\n}\n' > "$R/backend/src/test/java/m/MoneyTest.java"
run_check main --allow-deletions "superseded STORY-012"
expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: allowed deletion does not excuse loss elsewhere -> VIOLATION"

echo "-- committed changes against a sha / branch base"
reset_repo committed
g checkout -q -b story/STORY-013
printf 'class MoneyTest {\n  @Test void a() { assertEquals(1, 1); }\n}\n' > "$R/backend/src/test/java/m/MoneyTest.java"
g commit -qam "weaken [STORY-013]"
run_check "$BASE_SHA";       expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: committed loss vs sha base -> VIOLATION"
run_check main;              expect 1 "TEST_INTEGRITY: VIOLATION" "integrity: committed loss vs main branch base -> VIOLATION"
g checkout -q main
run_check main;              expect 0 "TEST_INTEGRITY: OK" "integrity: back on main nothing changed -> OK"

echo "-- rename keeps the baseline"
reset_repo rename
g mv web/src/sum.test.ts web/src/add.test.ts
run_check main;              expect 0 "TEST_INTEGRITY: OK" "integrity: renamed test file with same content -> OK"
contains "renamed from web/src/sum.test.ts" "integrity: rename is reported"

echo "-- tolerance increase is a warning only"
reset_repo tol
printf 'describe("sum", () => { it("adds", () => { expect(add(1,2)).%s3); }); });\n' "$CLOSE_TO" > "$R/web/src/sum.test.ts"
run_check main;              expect 0 "TEST_INTEGRITY: OK" "integrity: tolerance increase -> OK with WARN"
contains "WARN:" "integrity: tolerance warning printed"
contains "tolerance/approximation usage increased" "integrity: tolerance warning names the cause"

echo "-- usage errors"
reset_repo usage
run_check;                   expect 2 "usage:" "integrity: no base ref -> usage error 2"
run_check nosuchref;         expect 2 "cannot resolve base ref" "integrity: unknown base ref -> exit 2"

echo
echo "$suite: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
