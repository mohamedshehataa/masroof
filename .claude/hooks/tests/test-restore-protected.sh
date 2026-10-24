#!/usr/bin/env bash
# REQ-OPS-011: restore-protected.sh records the protected files before every Bash/PowerShell call and,
# while hooks are locked, undoes whatever the call changed (STORY-001 loop 3 integrity backstop).
# shellcheck source=lib-test.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib-test.sh"
H=restore-protected.sh

P="$(make_temp_repo)"
mkdir -p "$P/.claude/hooks/tests" "$P/docs" "$P/eval" "$P/backend/src"
printf '#!/usr/bin/env bash\necho guard v1\n' > "$P/.claude/hooks/guard-bash.sh"
printf '# lib\n' > "$P/.claude/hooks/lib.sh"
printf '{"hooks":{}}\n' > "$P/.claude/settings.json"
printf '# spec\n' > "$P/docs/PROMPT.md"
printf '{"cases":1}\n' > "$P/eval/baseline.json"
printf 'SDLC_AI_BUDGET_USD=300\nANTHROPIC_API_KEY=\n' > "$P/.env.example"
printf '.env\n.env.*\n!.env.example\n.claude/settings.local.json\n' > "$P/.gitignore"
(cd "$P" && git add . && git commit -q -m "feat: guardrails v1 [STORY-001]")
printf '#!/usr/bin/env bash\necho guard v2\n' > "$P/.claude/hooks/guard-bash.sh"
(cd "$P" && git commit -q -am "feat: guardrails v2 [STORY-001]")
(cd "$P" && git switch -q -c story/STORY-009-x)
export CLAUDE_PROJECT_DIR="$P"
SNAP="$MASROOF_STATE_DIR/protected-snapshot.json"
GUARD_V2="$(cat "$P/.claude/hooks/guard-bash.sh")"
WATCH=(.claude/hooks .claude/settings.json docs/PROMPT.md docs/PROMPT_DELIVERY_GATES.md eval/baseline.json)

J_PRE='{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"x"},"session_id":"t"}'
J_POST='{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"x"},"tool_response":{},"session_id":"t"}'
# rp <pre|post> [json] -> RC, OUT, ERR
rp() {
  local arg="" j="${2:-$J_POST}"
  [ "$1" = pre ] && { arg=--snapshot; j="${2:-$J_PRE}"; }
  OUT="$(printf '%s' "$j" | bash "$HOOKS_DIR/$H" $arg 2>"$TMP_ROOT/err.txt")"; RC=$?
  ERR="$(cat "$TMP_ROOT/err.txt")"
}
# in_p <shell command>: one "tool call" in the temp project between the PreToolUse and PostToolUse hooks
in_p() { rp pre; (cd "$P" && eval "$1") >/dev/null 2>&1; rp post; }
set_unlocked() { jq ".hooks_unlocked=$1" "$MASROOF_STATE_FILE" > "$MASROOF_STATE_FILE.tmp" && mv "$MASROOF_STATE_FILE.tmp" "$MASROOF_STATE_FILE"; }
porcelain() { (cd "$P" && git status --porcelain -- "${WATCH[@]}"); }
gone() { if [ -e "$1" ]; then echo present; else echo gone; fi; }

echo "-- snapshot and quiet calls (REQ-OPS-011)"
rp pre; assert_allowed "REQ-OPS-011 the PreToolUse snapshot exits 0"
assert_file_exists "$SNAP" "REQ-OPS-011 the snapshot is .claude/state/protected-snapshot.json"
assert_contains "$(cat "$SNAP")" '".claude/hooks/guard-bash.sh": "' "REQ-OPS-011 the snapshot records each hook's SHA-256"
in_p 'git status >/dev/null; ls .claude/hooks'
assert_allowed "REQ-OPS-011 a read-only call exits 0"
assert_eq "$OUT$ERR" "" "REQ-OPS-011 a read-only call prints nothing"
in_p 'echo "class A {}" > backend/src/A.java; echo note > docs/notes.md'
assert_eq "$OUT" "" "REQ-OPS-011 a call that changes only other files is not touched"
assert_eq "$(cat "$P/backend/src/A.java")" "class A {}" "REQ-OPS-011 the other files keep the call's change"

echo "-- writes guard-bash may miss are undone (REQ-OPS-011 unhappy paths)"
in_p 'echo hacked > .claude/hooks/guard-bash.sh'
assert_contains "$OUT" '"decision":"block"' "REQ-OPS-011 echo > hook: a blocking decision is printed"
assert_contains "$OUT" ".claude/hooks/guard-bash.sh" "REQ-OPS-011 echo > hook: the reason names the path"
assert_contains "$OUT" "make hooks-unlock" "REQ-OPS-011 the reason says hook edits need make hooks-unlock"
assert_eq "$(cat "$P/.claude/hooks/guard-bash.sh")" "$GUARD_V2" "REQ-OPS-011 echo > hook: content restored"
in_p "\"\$PYTHON\" -c \"open('.claude/hooks/guard-bash.sh','w').write('x')\""
assert_contains "$OUT" '"decision":"block"' "REQ-OPS-011 python open() write: blocking decision"
assert_eq "$(cat "$P/.claude/hooks/guard-bash.sh")" "$GUARD_V2" "REQ-OPS-011 python open() write: content restored"
in_p 'git checkout HEAD~1 -- .claude/hooks/guard-bash.sh'
assert_contains "$OUT" ".claude/hooks/guard-bash.sh (content restored)" "REQ-OPS-011 git checkout HEAD~1 -- hook: undone"
assert_eq "$(porcelain)" "" "REQ-OPS-011 git checkout HEAD~1 -- hook: git status shows no change (index too)"
in_p 'printf "{}" > .claude/settings.json; printf "# none" > docs/PROMPT.md; printf "{}" > eval/baseline.json'
assert_contains "$OUT" "docs/PROMPT.md" "REQ-OPS-011 several files in one call are all named"
assert_eq "$(porcelain)" "" "REQ-OPS-011 settings.json, PROMPT.md and baseline.json restored"
in_p 'printf "# none" > docs/PROMPT_DELIVERY_GATES.md'
assert_contains "$OUT" "docs/PROMPT_DELIVERY_GATES.md" "REQ-OPS-011 a write to the delivery-gates addendum is undone and named"
assert_eq "$(porcelain)" "" "REQ-OPS-011 delivery-gates addendum restored"
in_p 'rm -f .claude/hooks/lib.sh'
assert_eq "$(cat "$P/.claude/hooks/lib.sh" 2>/dev/null)" "# lib" "REQ-OPS-011 a deleted hook is restored"
in_p 'echo "exit 0" > .claude/hooks/evil.sh'
assert_contains "$OUT" ".claude/hooks/evil.sh (new file removed)" "REQ-OPS-011 a new .claude/hooks/evil.sh is named"
assert_eq "$(gone "$P/.claude/hooks/evil.sh")" "gone" "REQ-OPS-011 a new .claude/hooks/evil.sh is removed"
in_p 'echo x > .claude/hooks/staged.sh && git add .claude/hooks/staged.sh'
assert_eq "$(porcelain)" "" "REQ-OPS-011 a new hook file the call staged is removed from the index too"
in_p 'printf "{\"disableAllHooks\":true}" > .claude/settings.local.json'
assert_eq "$(gone "$P/.claude/settings.local.json")" "gone" "REQ-OPS-011 a new untracked settings.local.json is removed"

echo "-- Windows spellings of the same files"
if command -v cygpath >/dev/null 2>&1; then
  sdir="$(cygpath -s -m "$P/.claude" 2>/dev/null)"; [[ "$sdir" == *'~'* ]] || sdir="$P/.claude"   # 8.3 names may be off
  export J_SD="$sdir"
  in_p 'echo x > "$J_SD/hooks/guard-bash.sh"'
  assert_contains "$OUT" ".claude/hooks/guard-bash.sh (content restored)" "REQ-OPS-011 echo > CLAUDE~1/hooks/guard-bash.sh: undone, canonical name in the reason"
  assert_eq "$(cat "$P/.claude/hooks/guard-bash.sh")" "$GUARD_V2" "REQ-OPS-011 echo > CLAUDE~1/hooks/guard-bash.sh: content restored"
  export J_ADS='.claude/settings.json::$DATA'
  in_p "\"\$PYTHON\" -c \"import os; open(os.environ['J_ADS'],'w').write('{}')\""
  assert_contains "$OUT" ".claude/settings.json (content restored)" "REQ-OPS-011 a write to settings.json::\$DATA is undone"
  J_W="\\\\?\\$(cygpath -w "$P/.claude/hooks/lib.sh")"; export J_W
  in_p "\"\$PYTHON\" -c \"import os; open(os.environ['J_W'],'w').write('x')\""
  assert_contains "$OUT" ".claude/hooks/lib.sh (content restored)" "REQ-OPS-011 a write to \\\\?\\C:\\...\\lib.sh is undone"
  assert_eq "$(porcelain)" "" "REQ-OPS-011 after the Windows-spelling writes git status is clean"
fi
if command -v powershell.exe >/dev/null 2>&1; then
  in_p "powershell.exe -NoProfile -NonInteractive -Command \"[IO.File]::WriteAllText('.claude/settings.json','{}')\""
  assert_contains "$OUT" ".claude/settings.json (content restored)" "REQ-OPS-011 [IO.File]::WriteAllText is undone"
  assert_eq "$(cat "$P/.claude/settings.json")" '{"hooks":{}}' "REQ-OPS-011 [IO.File]::WriteAllText: content restored"
fi

echo "-- untracked protected files: reported, never restored or printed"
in_p 'cp .env.example .env'
assert_eq "$OUT" "" "REQ-OPS-011 cp .env.example .env is allowed"
assert_file_exists "$P/.env" "REQ-OPS-011 the bootstrap .env stays"
in_p 'echo ANTHROPIC_API_KEY=changed-in-call >> .env'
assert_contains "$OUT" ".env (changed (not restorable))" "REQ-OPS-011 a changed .env is reported as not restorable"
assert_contains "$(cat "$P/.env")" "changed-in-call" "REQ-OPS-011 the .env is not restored"
assert_not_contains "$OUT" "changed-in-call" "REQ-OPS-011 the decision never prints .env content"
assert_eq "$(find "$MASROOF_STATE_DIR" -type f -exec grep -l "changed-in-call" {} + 2>/dev/null)" "" "REQ-OPS-011 .env content is never copied into the state dir"
in_p 'echo X=1 > .env.local'
assert_eq "$(gone "$P/.env.local")" "gone" "REQ-OPS-011 a new untracked .env.local is removed"
in_p 'mkdir -p infra && echo X=1 > infra/.env.prod'
assert_eq "$(gone "$P/infra/.env.prod")" "gone" "REQ-OPS-011 a new nested .env.prod is removed"
MASROOF_HOOKS_UNLOCK=1 in_p 'printf "{}" > .claude/settings.local.json'
in_p 'printf "{\"disableAllHooks\":true}" > .claude/settings.local.json'
assert_contains "$OUT" ".claude/settings.local.json (changed (not restorable))" "REQ-OPS-011 an existing settings.local.json that changed is reported"

echo "-- unlocked, pre-existing and self-unlocking changes"
MASROOF_HOOKS_UNLOCK=1 in_p 'echo "# lib v2" > .claude/hooks/lib.sh'
assert_eq "$OUT" "" "REQ-OPS-011 MASROOF_HOOKS_UNLOCK=1: no decision"
assert_eq "$(cat "$P/.claude/hooks/lib.sh")" "# lib v2" "REQ-OPS-011 MASROOF_HOOKS_UNLOCK=1: the change stays"
set_unlocked true; in_p 'echo "# lib v3" > .claude/hooks/lib.sh'; set_unlocked false
assert_eq "$(cat "$P/.claude/hooks/lib.sh")" "# lib v3" "REQ-OPS-011 state.hooks_unlocked (make hooks-unlock): the change stays"
in_p 'git log -1 >/dev/null'
assert_eq "$OUT" "" "REQ-OPS-011 a tracked file modified before the call and not by it: no decision"
assert_eq "$(cat "$P/.claude/hooks/lib.sh")" "# lib v3" "REQ-OPS-011 a tracked file modified before the call is left as is"
in_p 'echo "# evil" > .claude/hooks/lib.sh'
assert_eq "$(cat "$P/.claude/hooks/lib.sh")" "# lib v3" "REQ-OPS-011 a call's change is undone to the pre-call content, not to HEAD"
in_p "jq '.hooks_unlocked=true' \"\$MASROOF_STATE_FILE\" > \"\$MASROOF_STATE_FILE.t\" && mv \"\$MASROOF_STATE_FILE.t\" \"\$MASROOF_STATE_FILE\"; echo '# evil' > .claude/hooks/lib.sh"
assert_eq "$(cat "$P/.claude/hooks/lib.sh")" "# lib v3" "REQ-OPS-011 a call that unlocks hooks itself is still judged locked"
set_unlocked false
(cd "$P" && git checkout -q -- .claude/hooks/lib.sh); rp pre

echo "-- branch changes are not writes; a commit in the same call is"
(cd "$P" && git switch -q -c story/STORY-010-y && printf '#!/usr/bin/env bash\necho guard v3\n' > .claude/hooks/guard-bash.sh && git commit -q -am "feat: v3 [STORY-010]" && git switch -q story/STORY-009-x)
in_p 'git switch -q story/STORY-010-y'
assert_eq "$OUT" "" "REQ-OPS-011 git switch to a branch with other hooks is not undone"
assert_contains "$(cat "$P/.claude/hooks/guard-bash.sh")" "guard v3" "REQ-OPS-011 after git switch the branch's hook is on disk"
in_p 'git switch -q story/STORY-009-x'
assert_contains "$(cat "$P/.claude/hooks/guard-bash.sh")" "guard v2" "REQ-OPS-011 switching back is not undone either"
in_p 'git checkout HEAD~1 -- .claude/hooks/guard-bash.sh && git commit -q -m "chore: old guard [STORY-009]"'
assert_contains "$OUT" '"decision":"block"' "REQ-OPS-011 a weakened hook committed in the same call is caught"
assert_contains "$(cat "$P/.claude/hooks/guard-bash.sh")" "guard v2" "REQ-OPS-011 a weakened hook committed in the same call is restored on disk"
assert_contains "$OUT" "git reset --soft HEAD~1" "REQ-OPS-011 the reason explains how to undo the commit"
(cd "$P" && git reset -q --soft HEAD~1 && git checkout -q HEAD -- .claude/hooks/guard-bash.sh); rp pre

echo "-- a poisoned backup blob is not trusted"
rp pre; h="$(sha256sum < "$P/.claude/hooks/guard-bash.sh")"; h="${h:0:64}"
printf 'poisoned\n' > "$MASROOF_STATE_DIR/protected-backup/$h"
(cd "$P" && echo hacked > .claude/hooks/guard-bash.sh); rp post
assert_eq "$(cat "$P/.claude/hooks/guard-bash.sh")" "$GUARD_V2" "REQ-OPS-011 a poisoned backup blob is ignored (restored from HEAD)"

echo "-- malformed input, not a work tree, timing"
rp post 'garbage'; assert_allowed "REQ-OPS-011 malformed JSON exits 0"
assert_eq "$(printf '%s\n' "$ERR" | wc -l | tr -d ' ')" "1" "REQ-OPS-011 malformed JSON prints exactly one stderr line"
NG="$TMP_ROOT/not-git"; mkdir -p "$NG"
CLAUDE_PROJECT_DIR="$NG" rp post; assert_allowed "REQ-OPS-011 not a git work tree exits 0"
assert_contains "$ERR" "not a git work tree" "REQ-OPS-011 not a git work tree says so"
assert_eq "$(printf '%s\n' "$ERR" | wc -l | tr -d ' ')" "1" "REQ-OPS-011 not a git work tree prints exactly one stderr line"
CLAUDE_PROJECT_DIR="$REPO_DIR" rp pre
s="$EPOCHREALTIME"; CLAUDE_PROJECT_DIR="$REPO_DIR" rp post; e="$EPOCHREALTIME"
MS="$(awk -v a="$s" -v b="$e" 'BEGIN { printf "%d", (b - a) * 1000 }')"
assert_eq "$(( MS < 2000 ))" "1" "REQ-OPS-011 the hook finishes in < 2 s on this repo (took ${MS} ms)"
finish
