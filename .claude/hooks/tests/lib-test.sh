#!/usr/bin/env bash
# Tiny test harness for the hook scripts. Source from each test-*.sh.
# Each test runs a hook with a crafted stdin JSON and asserts the exit code / output.
# shellcheck shell=bash
set -u
TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_DIR="$(cd "$TESTS_DIR/.." && pwd)"
REPO_DIR="$(cd "$HOOKS_DIR/../.." && pwd)"
# shellcheck source=../../../scripts/env.sh
. "$REPO_DIR/scripts/env.sh"
export LC_ALL=C.UTF-8 2>/dev/null || true

jq() { command jq "$@" | tr -d '\r'; }   # jq.exe emits CRLF on Windows
PASS=0; FAIL=0; FAILED_NAMES=()
RESULTS_TSV="${HOOK_TEST_RESULTS_TSV:-}"
SUITE_NAME="$(basename "${BASH_SOURCE[1]:-suite}" .sh)"

TMP_ROOT="$(mktemp -d)"
cleanup() { rm -rf "$TMP_ROOT"; }
trap cleanup EXIT

# Isolated state for every suite: never touch the real .claude/state.json
export MASROOF_STATE_FILE="$TMP_ROOT/state.json"
export MASROOF_STATE_DIR="$TMP_ROOT/state"
mkdir -p "$MASROOF_STATE_DIR"
cp "$REPO_DIR/.claude/state.example.json" "$MASROOF_STATE_FILE"
export CLAUDE_PROJECT_DIR="$REPO_DIR"
unset MASROOF_HOOKS_UNLOCK MASROOF_REBASELINE MASROOF_RELEASE_ACTIVE MASROOF_SKIP_STOP_GATE 2>/dev/null || true

RC=0; OUT=""; ERR=""
# run_hook <hook-file> <stdin-json>
run_hook() {
  local hook="$1" json="$2" errf="$TMP_ROOT/err.txt"
  OUT="$(printf '%s' "$json" | bash "$HOOKS_DIR/$hook" 2>"$errf")"; RC=$?
  ERR="$(cat "$errf")"
}

_record() { # _record <ok|fail> <name> <detail>
  if [ -n "$RESULTS_TSV" ]; then printf '%s\t%s\t%s\t%s\n' "$SUITE_NAME" "$1" "$2" "${3//$'\n'/ }" >> "$RESULTS_TSV"; fi
}
_pass() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; _record ok "$1" ""; }
_fail() { FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); printf '  FAIL %s\n       %s\n' "$1" "$2"; _record fail "$1" "$2"; }

assert_rc() { # assert_rc <expected> <name>
  if [ "$RC" -eq "$1" ]; then _pass "$2"; else _fail "$2" "expected rc=$1 got rc=$RC | stderr: ${ERR:0:400} | stdout: ${OUT:0:200}"; fi
}
assert_blocked() { assert_rc 2 "$1"; }
assert_allowed() { assert_rc 0 "$1"; }
assert_contains() { # assert_contains <haystack> <needle> <name>
  if [[ "$1" == *"$2"* ]]; then _pass "$3"; else _fail "$3" "expected to contain '$2' | got: ${1:0:400}"; fi
}
assert_not_contains() {
  if [[ "$1" != *"$2"* ]]; then _pass "$3"; else _fail "$3" "expected NOT to contain '$2' | got: ${1:0:400}"; fi
}
assert_eq() { if [ "$1" = "$2" ]; then _pass "$3"; else _fail "$3" "expected '$2' got '$1'"; fi; }
assert_file_exists() { if [ -e "$1" ]; then _pass "$2"; else _fail "$2" "missing file $1"; fi; }

# ---- JSON builders (mirror the documented hook input shapes) ----
# Paths are passed through the environment, not --arg: MSYS would rewrite /tmp/... arguments
# given to the native jq.exe, and env values are left alone.
json_bash() { # json_bash <command> [cwd]
  J_C="$1" J_CWD="${2:-$CLAUDE_PROJECT_DIR}" jq -n     '{hook_event_name:"PreToolUse",tool_name:"Bash",tool_input:{command:env.J_C},cwd:env.J_CWD,session_id:"test",permission_mode:"default"}'
}
json_write() { # json_write <file_path> <content> [agent_type]
  J_P="$1" J_C="$2" J_A="${3:-}" jq -n     '{hook_event_name:"PreToolUse",tool_name:"Write",tool_input:{file_path:env.J_P,content:env.J_C},cwd:"x",session_id:"test"} | if env.J_A=="" then . else .agent_type=env.J_A end'
}
json_edit() { # json_edit <file_path> <new_string> [agent_type]
  J_P="$1" J_N="$2" J_A="${3:-}" jq -n     '{hook_event_name:"PreToolUse",tool_name:"Edit",tool_input:{file_path:env.J_P,old_string:"old",new_string:env.J_N},cwd:"x",session_id:"test"} | if env.J_A=="" then . else .agent_type=env.J_A end'
}
json_multiedit() { # json_multiedit <file_path> <new_string>
  J_P="$1" J_N="$2" jq -n     '{hook_event_name:"PreToolUse",tool_name:"MultiEdit",tool_input:{file_path:env.J_P,edits:[{old_string:"a",new_string:"b"},{old_string:"c",new_string:env.J_N}]},cwd:"x",session_id:"test"}'
}
json_post_write() { # json_post_write <file_path> [agent_type]
  J_P="$1" J_A="${2:-}" jq -n     '{hook_event_name:"PostToolUse",tool_name:"Write",tool_input:{file_path:env.J_P,content:""},tool_response:{filePath:env.J_P,type:"update"},cwd:"x",session_id:"test"} | if env.J_A=="" then . else .agent_type=env.J_A end'
}
json_subagent_stop() { # json_subagent_stop <agent_type> <last_message>
  J_A="$1" J_M="$2" jq -n     '{hook_event_name:"SubagentStop",agent_type:env.J_A,agent_id:"a1",stop_hook_active:false,last_assistant_message:env.J_M,session_id:"test"}'
}
json_stop() { # json_stop [active]
  jq -n --argjson act "${1:-false}" '{hook_event_name:"Stop",stop_hook_active:$act,last_assistant_message:"done",session_id:"test"}'
}
json_session_start() { J_S="${1:-startup}" jq -n '{hook_event_name:"SessionStart",source:env.J_S,session_id:"test"}'; }
json_precompact() { J_T="${1:-auto}" jq -n '{hook_event_name:"PreCompact",trigger:env.J_T,session_id:"test"}'; }
json_prompt() { J_P="${1:-hi}" jq -n '{hook_event_name:"UserPromptSubmit",prompt:env.J_P,session_id:"test"}'; }

# make_temp_repo -> prints path of a fresh git repo on branch main with one commit
make_temp_repo() {
  local d="$TMP_ROOT/repo-$RANDOM$RANDOM"
  mkdir -p "$d" && (
    cd "$d" && git init -q -b main . && git config user.email t@t && git config user.name t \
    && git config commit.gpgsign false && git config core.autocrlf false && echo "# tmp" > README.md && git add . && git commit -q -m "init"
  ) >/dev/null 2>&1
  cp "$REPO_DIR/.claude/state.example.json" "$d/.claude-state.json" 2>/dev/null || true
  printf '%s' "$d"
}

finish() {
  printf '%s: %d passed, %d failed\n' "$SUITE_NAME" "$PASS" "$FAIL"
  if (( FAIL > 0 )); then printf '  failed: %s\n' "${FAILED_NAMES[*]}"; exit 1; fi
  exit 0
}
