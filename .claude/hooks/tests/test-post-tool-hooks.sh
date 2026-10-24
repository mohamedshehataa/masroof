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

echo "-- flips and comment-only checks with an unchanged count (STORY-001 loop 3, F-14)"
(cd "$P" && git checkout -q -- tests/test-guard.sh)
printf 'run_hook a; assert_allowed "x"\nrun_hook b; assert_allowed "y"\nassert_contains "$ERR" "z" "w"\nexpect 0 "OK" "v"\n' > "$BT"
run_hook test-integrity.sh "$(json_post_write "$BT" devops-engineer)"; assert_contains "$OUT" "assert_blocked 1 -> 0" "REQ-OPS-007 an assert_blocked -> assert_allowed flip warns although the count is equal"
printf 'run_hook a; assert_blocked "x"\nrun_hook b; assert_allowed "y"\nassert_contains "$ERR" "z" "w"\nexpect 1 "OK" "v"\n' > "$BT"
run_hook test-integrity.sh "$(json_post_write "$BT" devops-engineer)"; assert_contains "$OUT" "expect 0 1 -> 0" "REQ-OPS-007 an expect 0 -> expect 1 flip warns"
printf 'run_hook a; assert_blocked "x"\nrun_hook b; assert_allowed "y"\n# assert_contains "$ERR" "z" "w"\nexpect 0 "OK" "v"\nassert_eq 1 1 "extra"\n' > "$BT"
run_hook test-integrity.sh "$(json_post_write "$BT" devops-engineer)"; assert_contains "$OUT" "TEST INTEGRITY WARNING" "REQ-OPS-007 a check kept only in a comment counts as removed"
(cd "$P" && git checkout -q -- tests/test-guard.sh)

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

echo "-- one skip line per absent tool; built-in checks still run (STORY-001 loop 3, F-15)"
MASROOF_RUFF=no-such-ruff run_hook format-changed.sh "$(json_post_write "$PY")"
assert_eq "$ERR" "format-changed: ruff not installed, skipped" "REQ-OPS-010 .py without ruff: exactly one skip line"
assert_contains "$OUT" "scripts/bad.py:1: SyntaxError" "REQ-OPS-010 .py without ruff: the compile check still reports"
MASROOF_YAMLLINT=no-such-yamllint run_hook format-changed.sh "$(json_post_write "$Y")"
assert_eq "$ERR" "format-changed: yamllint not installed, skipped" "REQ-OPS-010 .yaml without yamllint: exactly one skip line"
assert_contains "$OUT" "invalid YAML" "REQ-OPS-010 .yaml without yamllint: the parse check still reports"
MASROOF_KTLINT=no-such-ktlint run_hook format-changed.sh "$(json_post_write "$(printf '%s/backend/A.kt' "$P"; mkdir -p "$P/backend"; printf 'class A\n' > "$P/backend/A.kt")")"
assert_eq "$ERR" "format-changed: ktlint not installed, skipped" "REQ-OPS-010 .kt without ktlint: exactly one skip line"
TS="$P/web/src/a.ts"; printf 'export const a = 1\n' > "$TS"
run_hook format-changed.sh "$(json_post_write "$TS")"
assert_eq "$(printf '%s\n' "$ERR" | sort | tr '\n' '|')" "format-changed: eslint not installed, skipped|format-changed: prettier not installed, skipped|" "REQ-OPS-010 .ts without prettier and eslint: exactly two skip lines"

echo "-- file names are data, never code (STORY-001 loop 3, F-07)"
FAKEBIN="$TMP_ROOT/fakebin"; mkdir -p "$FAKEBIN"
printf '#!/usr/bin/env bash\nfor a in "$@"; do f="$a"; done\nprintf "%%s:1:1: Unexpected blank line\\n" "$f"\n' > "$FAKEBIN/ktlint"; chmod +x "$FAKEBIN/ktlint"
WD="$TMP_ROOT/hookcwd"; mkdir -p "$WD"
for name in 'backend/x#;e touch PWNED;#.kt' 'backend/y$(touch PWNED).kt' "backend/z'\`touch PWNED\`.kt"; do   # no '"': NTFS cannot store it
  if printf 'class X\n' > "$P/$name" 2>/dev/null; then
    OUT="$(cd "$WD" && json_post_write "$P/$name" | PATH="$FAKEBIN:$PATH" bash "$HOOKS_DIR/format-changed.sh" 2>"$TMP_ROOT/err.txt")"
    assert_contains "$OUT" "$name:1: Unexpected blank line" "REQ-OPS-010 the finding starts with the literal name: $name"
    assert_eq "$(find "$WD" "$P" "$TMP_ROOT" -name PWNED 2>/dev/null | wc -l | tr -d ' ')" "0" "REQ-OPS-010 no PWNED file for: $name"
  fi
done
if command -v shellcheck >/dev/null 2>&1; then
  SN="$P/scripts/\$(touch PWNED).sh"; printf '#!/usr/bin/env bash\necho $1\n' > "$SN"
  OUT="$(cd "$WD" && json_post_write "$SN" | bash "$HOOKS_DIR/format-changed.sh" 2>/dev/null)"
  assert_contains "$OUT" "scripts/\$(touch PWNED).sh:2: " "REQ-OPS-010 a \$(...) shell name is reported literally"
  assert_eq "$(find "$WD" "$P" "$TMP_ROOT" -name PWNED 2>/dev/null | wc -l | tr -d ' ')" "0" "REQ-OPS-010 no PWNED file for a \$(...) shell name"
fi
finish
