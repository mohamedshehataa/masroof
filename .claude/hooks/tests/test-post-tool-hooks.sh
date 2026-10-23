#!/usr/bin/env bash
# REQ-OPS-007 test-integrity warns on weakened tests; REQ-OPS-010 format-changed gives lint feedback.
# shellcheck source=lib-test.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib-test.sh"

P="$(make_temp_repo)"
mkdir -p "$P/backend/src/test/java" "$P/web/src" "$P/scripts"
T="$P/backend/src/test/java/MoneyTest.java"
cat > "$T" <<'EOF'
class MoneyTest {
  @Test void a() { assertEquals(1, 1); assertTrue(true); }
  @Test void b() { assertThat(x).isEqualTo(y); }
}
EOF
(cd "$P" && git add . && git commit -q -m "test [PHASE-0]")
export CLAUDE_PROJECT_DIR="$P"

echo "-- test-integrity (REQ-OPS-007)"
run_hook test-integrity.sh "$(json_post_write "$T" backend-engineer)"; assert_allowed "REQ-OPS-007 unchanged test file: exit 0"
assert_eq "$OUT" "" "REQ-OPS-007 unchanged test file: no warning"
sed -i 's/assertTrue(true); //' "$T"
run_hook test-integrity.sh "$(json_post_write "$T" backend-engineer)"; assert_allowed "REQ-OPS-007 never blocks"
assert_contains "$OUT" "TEST INTEGRITY WARNING" "REQ-OPS-007 warns when assertions decrease"
assert_contains "$OUT" "3" "REQ-OPS-007 reports old count"
assert_file_exists "$MASROOF_STATE_DIR/test-integrity.log" "REQ-OPS-007 logs for the verifier"
assert_contains "$(cat "$MASROOF_STATE_DIR/test-integrity.log")" "backend-engineer" "REQ-OPS-007 log names the agent"
run_hook test-integrity.sh "$(json_post_write "$T" test-designer)"; assert_eq "$OUT" "" "REQ-OPS-007 test-designer is exempt"
(cd "$P" && git add . && git commit -q -m "test [PHASE-0]")
printf '  @Disabled @Test void c() { assertEquals(2, 2); }\n' >> "$T"
run_hook test-integrity.sh "$(json_post_write "$T")"; assert_contains "$OUT" "skip/disable" "REQ-OPS-007 warns on added @Disabled (main agent)"
W="$P/web/src/money.test.ts"; printf 'it.skip("x", () => { expect(1).toBe(1) })\n' > "$W"
run_hook test-integrity.sh "$(json_post_write "$W" web-engineer)"; assert_contains "$OUT" "skip/disable" "REQ-OPS-007 new test file with .skip warns"
S="$P/backend/src/main/java/Svc.java"; mkdir -p "$(dirname "$S")"; echo "class Svc {}" > "$S"
run_hook test-integrity.sh "$(json_post_write "$S" backend-engineer)"; assert_eq "$OUT" "" "REQ-OPS-007 non-test files are ignored"

echo "-- format-changed (REQ-OPS-010)"
B="$P/scripts/bad.sh"; printf '#!/usr/bin/env bash\necho $1\n' > "$B"
run_hook format-changed.sh "$(json_post_write "$B")"; assert_allowed "REQ-OPS-010 never blocks"
if command -v shellcheck >/dev/null 2>&1; then
  assert_contains "$OUT" "SC2086" "REQ-OPS-010 feeds shellcheck findings back"
  assert_contains "$OUT" "additionalContext" "REQ-OPS-010 uses hookSpecificOutput.additionalContext"
fi
G="$P/scripts/good.sh"; printf '#!/usr/bin/env bash\necho "$1"\n' > "$G"
run_hook format-changed.sh "$(json_post_write "$G")"; assert_eq "$OUT" "" "REQ-OPS-010 silent on clean file"
J="$P/web/bad.json"; printf '{"a": 1,}\n' > "$J"
run_hook format-changed.sh "$(json_post_write "$J")"; assert_contains "$OUT" "Lint feedback" "REQ-OPS-010 reports invalid JSON"
Y="$P/.github/bad.yml"; mkdir -p "$(dirname "$Y")"; printf 'a: [1, 2\n' > "$Y"
run_hook format-changed.sh "$(json_post_write "$Y")"; assert_contains "$OUT" "Lint feedback" "REQ-OPS-010 reports invalid YAML"
PY="$P/scripts/bad.py"; printf 'def x(:\n  pass\n' > "$PY"
run_hook format-changed.sh "$(json_post_write "$PY")"; assert_contains "$OUT" "Lint feedback" "REQ-OPS-010 reports Python syntax errors"
run_hook format-changed.sh "$(json_post_write "$P/does-not-exist.ts")"; assert_eq "$OUT" "" "REQ-OPS-010 missing file is ignored"
finish
