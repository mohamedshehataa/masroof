#!/usr/bin/env bash
# SubagentStop: a roster subagent may not finish unless its final message carries the output
# contract from output-contracts.json (a valid STATUS: line + the required sections).
# Non-roster agents (general-purpose, Explore, ...) pass through.
HOOK_NAME=check-output-contract
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
agent="$(json_get '.agent_type')"
[ -z "$agent" ] && exit 0
[ -f "$CONTRACTS_FILE" ] || exit 0

contract_field() {   # contract_field <agent> <status|required_sections>  -> newline list
  if have_jq; then
    jq -r --arg a "$1" --arg f "$2" '.agents[$a][$f] // [] | .[]' "$CONTRACTS_FILE" 2>/dev/null
  else
    "$PYTHON" -c 'import json,sys; d=json.load(open(sys.argv[1],encoding="utf-8")); [print(x) for x in d["agents"].get(sys.argv[2],{}).get(sys.argv[3],[])]' "$CONTRACTS_FILE" "$1" "$2" 2>/dev/null
  fi
}
allowed="$(contract_field "$agent" status)"
if [ -z "$allowed" ]; then             # not a roster agent: pass, but say so (REQ-OPS-005 unhappy path)
  printf "check-output-contract: '%s' is not a roster agent; not gated\n" "$agent" >&2
  exit 0
fi
sections="$(contract_field "$agent" required_sections)"
msg="$(json_get '.last_assistant_message')"

allowed_pipe="$(printf '%s' "$allowed" | tr '\n' '|' | sed 's/|$//')"
contract_help="Required by output-contracts.json for '$agent': end your final message with 'STATUS: <$allowed_pipe>' and include each section as a line starting with: $(printf '%s' "$sections" | sed 's/$/:/' | tr '\n' ' ')"

[ -z "$msg" ] && block "final message is empty. $contract_help"
# lines inside ``` / ~~~ code fences are examples, not the contract
body="$(printf '%s\n' "$msg" | awk '/^[[:space:]]*(```|~~~)/ { f = !f; next } !f')"

status_val="$(printf '%s\n' "$body" | grep -E '^[[:space:]]*(\*\*)?STATUS(\*\*)?[[:space:]]*:' | tail -1 | sed -E 's/^[[:space:]]*(\*\*)?STATUS(\*\*)?[[:space:]]*:[[:space:]]*//; s/\*\*//g; s/`//g; s/^[[:space:]]+//; s/[[:space:]]+$//')"
[ -z "$status_val" ] && block "no 'STATUS:' line found in the final message (outside code fences). $contract_help"
case "$status_val" in
  *"|"*) block "the STATUS line '$status_val' is the contract template, not a result - pick exactly one value. $contract_help" ;;
esac
status_val="${status_val%% *}"
case "|$allowed_pipe|" in
  *"|$status_val|"*) : ;;
  *) block "STATUS value '$status_val' is not allowed for '$agent' (allowed: $allowed_pipe). $contract_help" ;;
esac

missing=""
while IFS= read -r s; do
  [ -z "$s" ] && continue
  printf '%s\n' "$body" | grep -Eq "^[[:space:]]*(#+[[:space:]]*)?(\*\*)?$s(\*\*)?[[:space:]]*:" || missing="${missing:+$missing, }$s"
done <<< "$sections"
[ -n "$missing" ] && block "final message is missing required section(s): $missing. $contract_help"

# STATUS must be the last non-empty line (so the orchestrator can parse it reliably)
last_line="$(printf '%s\n' "$msg" | grep -vE '^[[:space:]]*$' | tail -1)"
printf '%s' "$last_line" | grep -Eq '^[[:space:]]*(\*\*)?STATUS(\*\*)?[[:space:]]*:' \
  || block "the 'STATUS:' line must be the LAST line of your final message (found other text after it). $contract_help"

log_hook "OK agent=$agent status=$status_val"
exit 0
