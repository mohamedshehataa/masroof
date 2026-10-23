#!/usr/bin/env bash
# Shared helpers for Claude Code hooks. Source this; never execute it.
# Conventions (verified against code.claude.com/docs/en/hooks, 2026-10):
#   - stdin is one JSON object; fields differ per event (tool_name, tool_input, agent_type, ...)
#   - exit 2 + stderr  => block (PreToolUse / Stop / SubagentStop); Claude sees stderr
#   - exit 0 + JSON on stdout => structured output (additionalContext, systemMessage)
#   - never print anything else to stdout
# Source files stay ASCII-only (shellcheck on Windows cannot print non-ASCII lines).
# shellcheck shell=bash
set -o pipefail
# no $(cd ... && pwd): every hook sources this and each fork costs ~30 ms on Windows
HOOKS_DIR="${BASH_SOURCE[0]%/*}"
[ "$HOOKS_DIR" = "${BASH_SOURCE[0]}" ] && HOOKS_DIR=.
case "$HOOKS_DIR" in /*|[A-Za-z]:*) ;; *) HOOKS_DIR="$PWD/$HOOKS_DIR" ;; esac
# shellcheck source=../../scripts/env.sh
. "$HOOKS_DIR/../../scripts/env.sh"
: "${HOOK_NAME:=hook}"
STATE_FILE="${MASROOF_STATE_FILE:-$PROJECT_DIR/.claude/state.json}"
STATE_DIR="${MASROOF_STATE_DIR:-$PROJECT_DIR/.claude/state}"
CONTRACTS_FILE="${MASROOF_CONTRACTS_FILE:-$HOOKS_DIR/output-contracts.json}"
export CONTRACTS_FILE
# UTF-8 middle dot: the field separator used in docs/PROGRESS.md lines (kept out of source as a literal)
DOT=$'\302\267'
export DOT
[ -d "$STATE_DIR" ] || mkdir -p "$STATE_DIR" 2>/dev/null || true
HOOK_INPUT=""

# $(< /dev/stdin) is read by bash itself (no cat fork, unlike byte-wise `read` it is fast on pipes)
read_input() { HOOK_INPUT="$(< /dev/stdin)" 2>/dev/null || HOOK_INPUT=""; }

have_jq() { command -v jq >/dev/null 2>&1; }
# jq.exe on Windows prints CRLF; strip CR so values compare cleanly (no-op elsewhere). pipefail keeps jq's status.
jq() { command jq "$@" | tr -d '\r'; }

# json_get '.tool_input.command'  -> raw string or empty
json_get() {
  local path="$1"
  if have_jq; then
    printf '%s' "$HOOK_INPUT" | jq -r "$path // empty" 2>/dev/null
  else
    printf '%s' "$HOOK_INPUT" | "$PYTHON" -c '
import json,sys
path=[p for p in sys.argv[1].split(".") if p]
try:
    o=json.load(sys.stdin)
except Exception:
    sys.exit(0)
for p in path:
    if isinstance(o,dict) and p in o: o=o[p]
    else: sys.exit(0)
if o is None: print("")
elif isinstance(o,bool): print(str(o).lower())
elif isinstance(o,(dict,list)): print(json.dumps(o))
else: print(o)
' "$path" 2>/dev/null
  fi
}

# state_get key -> value or empty
state_get() {
  [ -f "$STATE_FILE" ] || return 0
  if have_jq; then jq -r --arg k "$1" '.[$k] // empty' "$STATE_FILE" 2>/dev/null
  else "$PYTHON" -c 'import json,sys; d=json.load(open(sys.argv[1])); v=d.get(sys.argv[2]); print("" if v is None else (str(v).lower() if isinstance(v,bool) else v))' "$STATE_FILE" "$1" 2>/dev/null
  fi
}

# state_set key value  (numbers/bools/null kept typed)
state_set() {
  local k="$1" v="$2" tmp now
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ ! -f "$STATE_FILE" ]; then
    if [ -f "$PROJECT_DIR/.claude/state.example.json" ]; then cp "$PROJECT_DIR/.claude/state.example.json" "$STATE_FILE"; else printf '{}\n' > "$STATE_FILE"; fi
  fi
  tmp="$(mktemp 2>/dev/null || printf '%s.tmp' "$STATE_FILE")"
  if have_jq; then
    if [[ "$v" =~ ^(-?[0-9]+|true|false|null)$ ]]; then
      jq --arg k "$k" --argjson v "$v" --arg now "$now" '.[$k]=$v | .updated_at=$now' "$STATE_FILE" > "$tmp" 2>/dev/null
    else
      jq --arg k "$k" --arg v "$v" --arg now "$now" '.[$k]=$v | .updated_at=$now' "$STATE_FILE" > "$tmp" 2>/dev/null
    fi
  else
    "$PYTHON" -c '
import json,sys
f,k,v,out,now=sys.argv[1:6]
d=json.load(open(f))
if v in ("true","false"): d[k]=(v=="true")
elif v=="null": d[k]=None
else:
    try: d[k]=int(v)
    except ValueError: d[k]=v
d["updated_at"]=now
open(out,"w").write(json.dumps(d,indent=2)+"\n")
' "$STATE_FILE" "$k" "$v" "$tmp" "$now"
  fi
  [ -s "$tmp" ] && mv "$tmp" "$STATE_FILE"
}

log_hook() { printf '%s %s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$HOOK_NAME" "$1" >> "$STATE_DIR/hooks.log" 2>/dev/null || true; }

# block "<reason>"  -> exit 2; reason goes to Claude via stderr
block() {
  log_hook "BLOCK $1"
  printf 'BLOCKED by %s: %s\n' "$HOOK_NAME" "$1" >&2
  exit 2
}

# feedback "<text>" [event]  -> non-blocking context for Claude, exit 0
feedback() {
  local msg="$1" event="${2:-PostToolUse}"
  log_hook "FEEDBACK ${msg//$'\n'/ | }"
  if have_jq; then
    jq -n --arg e "$event" --arg m "$msg" '{hookSpecificOutput:{hookEventName:$e,additionalContext:$m}}'
  else
    "$PYTHON" -c 'import json,sys; print(json.dumps({"hookSpecificOutput":{"hookEventName":sys.argv[1],"additionalContext":sys.argv[2]}}))' "$event" "$msg"
  fi
  exit 0
}

# system_message "<text>" -> shown to the user, exit 0
system_message() {
  if have_jq; then jq -n --arg m "$1" '{systemMessage:$m}'
  else "$PYTHON" -c 'import json,sys; print(json.dumps({"systemMessage":sys.argv[1]}))' "$1"; fi
  exit 0
}

# Canonical absolute path for prefix comparisons. On Windows (Git Bash) `cygpath -m` resolves
# mounts (/tmp -> C:/Users/.../Temp) and backslashes into one mixed form, so a path from the
# tool input (C:\x\y), from CLAUDE_PROJECT_DIR and from mktemp all compare equal. Elsewhere it
# just normalises separators. Relative paths are returned unchanged.
to_posix_path() {
  local p="${1//\\//}"
  case "$p" in
    /*|[A-Za-z]:/*)
      if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$p" 2>/dev/null || printf '%s' "$p"
      else
        printf '%s' "$p"
      fi ;;
    *) printf '%s' "$p" ;;
  esac
}

# rel_to_project <path> -> path relative to PROJECT_DIR, or the canonical absolute path if outside.
# The prefix test is case-insensitive (NTFS and APFS are case-insensitive, so C:/PERSONAL/x and
# c:\personal\x name the same file); the returned relative part keeps the caller's spelling, so
# callers that match patterns must lower-case it themselves (${rel,,}).
rel_to_project() {
  local p root
  p="$(to_posix_path "$1")"
  root="$(to_posix_path "$PROJECT_DIR")"
  root="${root%/}"
  case "${p,,}" in
    "${root,,}"/*) printf '%s' "${p:${#root}+1}" ;;
    *) printf '%s' "$p" ;;
  esac
}

# json_valid -> 0 when HOOK_INPUT is a JSON object (used to warn instead of silently passing)
json_valid() {
  [ -n "$HOOK_INPUT" ] || return 1
  if have_jq; then
    printf '%s' "$HOOK_INPUT" | command jq -e 'type == "object"' >/dev/null 2>&1
  else
    printf '%s' "$HOOK_INPUT" | "$PYTHON" -c 'import json,sys; sys.exit(0 if isinstance(json.loads(sys.stdin.buffer.read().decode("utf-8","replace")),dict) else 1)' >/dev/null 2>&1
  fi
}

# json_fields '.a' '.b.c' ...  -> NUL-separated: "ok" then each value ("" when missing; non-strings as JSON).
# Prints nothing at all when HOOK_INPUT is not a JSON object, so callers can tell a parse error apart.
# One process for several fields (each jq/python start costs ~30-60 ms on Windows).
json_fields() {
  if have_jq; then
    local prog='if type == "object" then "ok", "\u0000"' p
    for p in "$@"; do prog+=", ($p // \"\" | if type == \"string\" then . else tojson end), \"\\u0000\""; done
    prog+=' else empty end'
    printf '%s' "$HOOK_INPUT" | command jq -j "$prog" 2>/dev/null
  else
    printf '%s' "$HOOK_INPUT" | "$PYTHON" -c '
import json,sys
try:
    d=json.loads(sys.stdin.buffer.read().decode("utf-8","replace"))
except Exception:
    sys.exit(0)
if not isinstance(d,dict): sys.exit(0)
out=["ok"]
for path in sys.argv[1:]:
    o=d
    for k in [x for x in path.split(".") if x]:
        o=o.get(k) if isinstance(o,dict) else None
    out.append("" if o is None else (o if isinstance(o,str) else json.dumps(o)))
sys.stdout.buffer.write(("\0".join(out)+"\0").encode("utf-8"))
' "$@" 2>/dev/null
  fi
}

is_truthy() { case "${1,,}" in 1|true|yes|on) return 0 ;; *) return 1 ;; esac; }
