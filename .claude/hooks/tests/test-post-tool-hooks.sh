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

echo "-- bash suites count as assertions (STORY-001 verify F-11)"
BT="$P/tests/test-guard.sh"; mkdir -p "$(dirname "$BT")"
printf 'run_hook a; assert_blocked "x"\nrun_hook b; assert_allowed "y"\nassert_contains "$ERR" "z" "w"\nexpect 0 "OK" "v"\n' > "$BT"
(cd "$P" && git add . && git commit -q -m "test [PHASE-0]")
printf 'run_hook a; assert_blocked "x"\n' > "$BT"
run_hook test-integrity.sh "$(json_post_write "$BT" devops-engineer)"; assert_contains "$OUT" "assertions decreased 4 -> 1" "REQ-OPS-007 counts assert_* and expect <rc> in bash suites"

echo "-- case-variant path still compares against HEAD (F-12)"
(cd "$P" && git checkout -q -- tests/test-guard.sh)
cp "$T" "$T.orig"
sed -i 's/assertEquals(1, 1); //' "$T"
TW="$(cygpath -m "$T" 2>/dev/null || printf '%s' "$T")"
TL="${TW,,}"
if [ -f "$TL" ]; then   # case-insensitive filesystem (Windows, macOS); on Linux the lower-cased path does not exist
  run_hook test-integrity.sh "$(json_post_write "$TL" backend-engineer)"; assert_contains "$OUT" "assertions decreased" "REQ-OPS-007 lower-cased path of a weakened test still warns"
fi
mv "$T.orig" "$T"

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

echo "-- findings are file:line: message; a missing linter says so (STORY-001 verify F-18)"
if command -v shellcheck >/dev/null 2>&1; then
  run_hook format-changed.sh "$(json_post_write "$B")"; assert_contains "$OUT" "scripts/bad.sh:2: " "REQ-OPS-010 shellcheck finding is file:line: message"
  Q="$P/scripts/it's.sh"; printf '#!/usr/bin/env bash\necho $1\n' > "$Q"
  run_hook format-changed.sh "$(json_post_write "$Q")"; assert_contains "$OUT" "scripts/it's.sh:2: " "REQ-OPS-010 a quote in the file name is passed as data, not code"
fi
run_hook format-changed.sh "$(json_post_write "$PY")"; assert_contains "$OUT" "scripts/bad.py:1: SyntaxError" "REQ-OPS-010 Python finding is file:line: message"
run_hook format-changed.sh "$(json_post_write "$J")"; assert_contains "$OUT" "web/bad.json:1: invalid JSON" "REQ-OPS-010 JSON finding is file:line: message"
run_hook format-changed.sh "$(json_post_write "$Y")"; assert_contains "$OUT" ".github/bad.yml:" "REQ-OPS-010 YAML finding names the file"
assert_contains "$OUT" "invalid YAML" "REQ-OPS-010 YAML finding says what is wrong"
MASROOF_SHELLCHECK=no-such-shellcheck run_hook format-changed.sh "$(json_post_write "$B")"; assert_allowed "REQ-OPS-010 missing shellcheck exits 0"
assert_eq "$ERR" "format-changed: shellcheck not installed, skipped" "REQ-OPS-010 missing shellcheck prints exactly one skip line"
assert_eq "$OUT" "" "REQ-OPS-010 missing shellcheck gives no lint feedback"
finish
