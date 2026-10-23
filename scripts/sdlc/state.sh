#!/usr/bin/env bash
# Tiny state helper used by skills and hooks.
#   state.sh get <key>            -> prints value (raw)
#   state.sh set <key> <value>    -> sets JSON value (numbers/bools/null kept, else string)
#   state.sh show
#   state.sh unlock-hooks | lock-hooks | release-on | release-off | rebaseline-on | rebaseline-off
set -euo pipefail
# shellcheck source=../env.sh
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
jq() { command jq "$@" | tr -d '\r'; }   # jq.exe emits CRLF on Windows
set -o pipefail
STATE_FILE="${MASROOF_STATE_FILE:-$PROJECT_DIR/.claude/state.json}"
[ -f "$STATE_FILE" ] || cp "$PROJECT_DIR/.claude/state.example.json" "$STATE_FILE"
cmd="${1:-}"; shift || true
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
set_kv() {
  local k="$1" v="$2" tmp
  tmp="$(mktemp)"
  if [[ "$v" =~ ^(-?[0-9]+|true|false|null)$ ]]; then
    jq --arg k "$k" --argjson v "$v" --arg now "$now" '.[$k]=$v | .updated_at=$now' "$STATE_FILE" > "$tmp"
  else
    jq --arg k "$k" --arg v "$v" --arg now "$now" '.[$k]=$v | .updated_at=$now' "$STATE_FILE" > "$tmp"
  fi
  mv "$tmp" "$STATE_FILE"
}
case "$cmd" in
  get) jq -r --arg k "$1" '.[$k]' "$STATE_FILE" ;;
  set) set_kv "$1" "$2" ;;
  unlock-hooks) set_kv hooks_unlocked true ;;
  lock-hooks) set_kv hooks_unlocked false ;;
  release-on) set_kv release_active true ;;
  release-off) set_kv release_active false ;;
  rebaseline-on) set_kv rebaseline_allowed true ;;
  rebaseline-off) set_kv rebaseline_allowed false ;;
  show) cat "$STATE_FILE" ;;
  *) echo "usage: state.sh get|set|show|unlock-hooks|lock-hooks|release-on|release-off|rebaseline-on|rebaseline-off" >&2; exit 64 ;;
esac
