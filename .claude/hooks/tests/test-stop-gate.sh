#!/usr/bin/env bash
# REQ-OPS-004: stop-gate.sh blocks stopping while make check-fast is red (max 3), then escalates.
# shellcheck source=lib-test.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib-test.sh"
H=stop-gate.sh

P="$(make_temp_repo)"
mkdir -p "$P/docs" "$P/backend" "$P/.claude"
cp "$REPO_DIR/.claude/state.example.json" "$P/.claude/state.example.json"
printf '# Progress\n' > "$P/docs/PROGRESS.md"
printf '# Escalations\n\n## Open\n' > "$P/docs/ESCALATIONS.md"
(cd "$P" && git add . && git commit -q -m "docs [PHASE-0]")
export CLAUDE_PROJECT_DIR="$P"

# fake make: exit code from $FAKE_MAKE_RC file, records every invocation
FAKE="$TMP_ROOT/fakemake.sh"
cat > "$FAKE" <<'EOF'
#!/usr/bin/env bash
echo "fake make $*"
echo "invoked $*" >> "$FAKE_MAKE_LOG"
echo "FAIL: ExpenseTest.splitsSumToTotal expected 100 but was 99"
exit "$(cat "$FAKE_MAKE_RC")"
EOF
chmod +x "$FAKE"
export MAKE="$FAKE" FAKE_MAKE_RC="$TMP_ROOT/rc" FAKE_MAKE_LOG="$TMP_ROOT/make.log"
echo 1 > "$FAKE_MAKE_RC"; : > "$FAKE_MAKE_LOG"

echo "-- clean tree: nothing to check"
run_hook $H "$(json_stop)";            assert_allowed "REQ-OPS-004 clean tree passes without running make"
assert_eq "$(wc -l < "$FAKE_MAKE_LOG" | tr -d ' ')" "0" "REQ-OPS-004 make not invoked on clean tree"

echo "-- docs-only change: no gate"
echo "note" > "$P/docs/x.md"
run_hook $H "$(json_stop)";            assert_allowed "REQ-OPS-004 docs-only change passes"
assert_eq "$(wc -l < "$FAKE_MAKE_LOG" | tr -d ' ')" "0" "REQ-OPS-004 make not invoked for docs-only change"

echo "-- red backend change: blocked 1, 2, then escalated on 3"
echo "class X {}" > "$P/backend/X.java"
run_hook $H "$(json_stop)";            assert_blocked "REQ-OPS-004 block #1 when check-fast fails"
assert_contains "$ERR" "block 1/3" "REQ-OPS-004 block message shows counter 1/3"
assert_contains "$ERR" "MODULE=\"backend\"" "REQ-OPS-004 block names the module"
assert_contains "$ERR" "ExpenseTest" "REQ-OPS-004 block includes the failure tail"
assert_eq "$(jq -r .stop_blocks "$MASROOF_STATE_FILE")" "1" "REQ-OPS-004 state counter = 1"
run_hook $H "$(json_stop true)";       assert_blocked "REQ-OPS-004 block #2 (stop_hook_active=true still re-checks)"
assert_contains "$ERR" "block 2/3" "REQ-OPS-004 counter 2/3"
run_hook $H "$(json_stop true)";       assert_allowed "REQ-OPS-004 third failure lets the stop through"
assert_contains "$OUT" "systemMessage" "REQ-OPS-004 third failure emits a systemMessage"
assert_contains "$(cat "$P/docs/PROGRESS.md")" "RED" "REQ-OPS-004 RED entry appended to PROGRESS.md"
assert_contains "$(cat "$P/docs/ESCALATIONS.md")" "GATE-" "REQ-OPS-004 escalation appended to ESCALATIONS.md"
assert_eq "$(jq -r .stop_blocks "$MASROOF_STATE_FILE")" "0" "REQ-OPS-004 counter reset after escalation"
assert_eq "$(wc -l < "$FAKE_MAKE_LOG" | tr -d ' ')" "3" "REQ-OPS-004 make invoked exactly 3 times"

echo "-- green: records last_green and skips re-runs until the tree changes"
echo 0 > "$FAKE_MAKE_RC"; : > "$FAKE_MAKE_LOG"
run_hook $H "$(json_stop)";            assert_allowed "REQ-OPS-004 green check-fast allows stop"
assert_contains "$(jq -r .last_green "$MASROOF_STATE_FILE")" "@" "REQ-OPS-004 last_green fingerprint recorded"
run_hook $H "$(json_stop)";            assert_allowed "REQ-OPS-004 unchanged tree passes again"
assert_eq "$(wc -l < "$FAKE_MAKE_LOG" | tr -d ' ')" "1" "REQ-OPS-004 make not re-run for an unchanged tree"
echo "class Y {}" > "$P/backend/Y.java"
run_hook $H "$(json_stop)";            assert_allowed "REQ-OPS-004 new change re-runs the (green) gate"
assert_eq "$(wc -l < "$FAKE_MAKE_LOG" | tr -d ' ')" "2" "REQ-OPS-004 make re-run after a change"

echo "-- module mapping"
: > "$FAKE_MAKE_LOG"; rm -f "$P/backend/X.java" "$P/backend/Y.java"
mkdir -p "$P/web/src" "$P/scripts"; echo "x" > "$P/web/src/a.ts"; echo "y" > "$P/scripts/z.sh"
run_hook $H "$(json_stop)";            assert_allowed "REQ-OPS-004 mixed change runs"
assert_contains "$(cat "$FAKE_MAKE_LOG")" "MODULE=sdlc web" "REQ-OPS-004 web + sdlc modules detected (sorted)"

echo "-- escape hatch"
echo 1 > "$FAKE_MAKE_RC"; echo "z" > "$P/web/src/b.ts"
MASROOF_SKIP_STOP_GATE=1 run_hook $H "$(json_stop)"; assert_allowed "REQ-OPS-004 MASROOF_SKIP_STOP_GATE=1 bypasses (used by selftest only)"

echo "-- not a git repo"
NG="$TMP_ROOT/nogit"; mkdir -p "$NG"
CLAUDE_PROJECT_DIR="$NG" run_hook $H "$(json_stop)"; assert_allowed "REQ-OPS-004 non-git dir passes"
finish
