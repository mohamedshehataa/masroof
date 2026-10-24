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

# Canonical path for prefix comparisons (STORY-001 loop 3: every alias of a file maps to one name).
#   - backslashes -> '/'; the \\?\ \\.\ and \??\ device prefixes are dropped
#   - NTFS stream suffixes are dropped from every component (settings.json::$DATA, lib.sh:x, dir::$INDEX_ALLOCATION)
#   - on Windows, trailing dots/spaces of a component are dropped (Win32 ignores them: settings.json. == settings.json)
#   - empty and '.' components are dropped and '..' is resolved lexically (x/../.claude//hooks -> .claude/hooks)
#   - absolute paths on Windows: `cygpath -m` resolves mounts (/tmp -> C:/Users/.../Temp) so tool input
#     (C:\x\y), CLAUDE_PROJECT_DIR and mktemp paths compare equal; a path with '~' (8.3 short names such as
#     CLAUDE~1 or SETTIN~1.JSO) is expanded with `cygpath -l` on its deepest existing ancestor.
# Relative paths stay relative (normalised). Comparisons must still be case-insensitive (${x,,}).
norm_lex() { # norm_lex <path> -> NORM (lexical part of to_posix_path, no fork); NORM_ABS=1 when absolute
  local p="${1//\\//}" drive="" lead="" c out="" comps=() stack=()
  case "$p" in
    //\?/[Uu][Nn][Cc]/*|//./[Uu][Nn][Cc]/*) p="//${p:8}" ;;
    //\?/*|//./*|/\?\?/*) p="${p:4}" ;;
  esac
  case "$p" in [A-Za-z]:/*|[A-Za-z]:) drive="${p:0:2}"; p="${p:2}" ;; esac
  case "$p" in //*) if [ -z "$drive" ]; then lead="//"; else lead="/"; fi ;; /*) lead="/" ;; esac
  IFS=/ read -r -a comps <<< "$p"
  for c in "${comps[@]+"${comps[@]}"}"; do
    c="${c%%:*}"
    if [ "$_lib_win" = 1 ] && [[ "$c" =~ [^.] ]]; then
      while [[ "$c" == *[.\ ] ]]; do c="${c%?}"; done
    fi
    case "$c" in ''|.) continue ;; esac
    if [ "$c" = .. ]; then
      if (( ${#stack[@]} > 0 )) && [ "${stack[${#stack[@]}-1]}" != .. ]; then unset "stack[$(( ${#stack[@]} - 1 ))]"
      elif [ -z "$lead" ]; then stack+=(..)
      fi
      continue
    fi
    stack+=("$c")
  done
  for c in "${stack[@]+"${stack[@]}"}"; do out+="/$c"; done   # no $( ) here: every subshell is a fork
  NORM="$drive$lead${out#/}"; NORM_ABS=0
  [ -n "$lead" ] && NORM_ABS=1
  [ -z "$NORM" ] && NORM=.
  return 0
}
_lib_win=0; command -v cygpath >/dev/null 2>&1 && _lib_win=1
# to_posix_path_v <path> -> TPP (same as to_posix_path, without the $( ) fork of the caller; cygpath runs
# only for 8.3 names and non-drive mounts such as /tmp)
to_posix_path_v() {
  local out q rest=""
  TPP=""
  [ -z "$1" ] && return 0
  norm_lex "$1"; out="$NORM"
  if [ "$NORM_ABS" = 1 ] && [ "$_lib_win" = 1 ]; then
    if [[ "$out" == *'~'* ]]; then
      q="$out"
      while [ ! -e "$q" ] && [[ "$q" == */?* ]]; do rest="/${q##*/}$rest"; q="${q%/*}"; done
      q="$(cygpath -l -m "${q:-/}" 2>/dev/null || printf '%s' "$q")"
      TPP="${q%/}$rest"
    elif [[ "$out" =~ ^[A-Za-z]:(/|$) ]]; then
      TPP="$out"   # already the drive form: no fork
    elif [[ "$out" =~ ^/([A-Za-z])(/|$) ]] && [ -d "/${BASH_REMATCH[1]}" ]; then
      q="${BASH_REMATCH[1]}"; rest="${out:2}"; TPP="${q^^}:${rest:-/}"   # Git Bash drive mount /c/x -> C:/x
    else
      # cygpath drops glob characters (/tmp/* -> C:/.../Temp/), so only the part before the first glob goes through it
      q="$out"
      while [[ "$q" == *[\*\?\[]* ]] && [[ "$q" == */* ]]; do rest="/${q##*/}$rest"; q="${q%/*}"; done
      q="$(cygpath -m "${q:-/}" 2>/dev/null || printf '%s' "$q")"
      TPP="${q%/}$rest"
    fi
  else
    TPP="$out"
  fi
}
to_posix_path() { to_posix_path_v "$1"; printf '%s' "$TPP"; }

# rel_to_project <path> -> path relative to PROJECT_DIR, or the canonical absolute path if outside.
# The prefix test is case-insensitive (NTFS and APFS are case-insensitive, so C:/PERSONAL/x and
# c:\personal\x name the same file); the returned relative part keeps the caller's spelling, so
# callers that match patterns must lower-case it themselves (${rel,,}).
rel_to_project() {
  local p root
  to_posix_path_v "$1"; p="$TPP"
  to_posix_path_v "$PROJECT_DIR"; root="$TPP"
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
    command jq -j "$prog" <<< "$HOOK_INPUT" 2>/dev/null   # here-string: no extra printf process
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
