#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit): block writes whose content looks like a secret.
# Never echoes the matched secret back. Exit 2 = block.
HOOK_NAME=scan-secrets
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
read_input
content="$(json_get '.tool_input.content')"
[ -z "$content" ] && content="$(json_get '.tool_input.new_string')"
if [ -z "$content" ] && have_jq; then
  content="$(printf '%s' "$HOOK_INPUT" | jq -r '[.tool_input.edits[]?.new_string] | join("\n")' 2>/dev/null)"
fi
[ -z "$content" ] && exit 0
fp="$(json_get '.tool_input.file_path')"
rel="$(rel_to_project "${fp:-unknown}")"

in_fixture_zone=false
case "$rel" in fixtures/*|eval/datasets/*|*/src/test/*|*/__tests__/*|*.test.ts|*.test.tsx|*.spec.ts) in_fixture_zone=true ;; esac

hit() { block "content for '$rel' matches secret pattern [$1] - never write real keys, tokens, private keys, card numbers or personal phone numbers; use .env (gitignored) and placeholders like 'changeme' or anonymized fixtures"; }

# ---- vendor key formats ----
printf '%s' "$content" | grep -Eq 'sk-ant-[A-Za-z0-9_-]{20,}'                                  && hit "anthropic-api-key"
printf '%s' "$content" | grep -Eq '(^|[^A-Za-z0-9])sk-(proj-|live-|test-)?[A-Za-z0-9_-]{32,}'   && hit "openai-style-key"
printf '%s' "$content" | grep -Eq '(^|[^A-Za-z0-9])EAA[A-Za-z0-9]{40,}'                        && hit "meta-access-token"
printf '%s' "$content" | grep -Eq 'gh[pousr]_[A-Za-z0-9]{36,}'                                  && hit "github-token"
printf '%s' "$content" | grep -Eq 'github_pat_[A-Za-z0-9_]{60,}'                                && hit "github-fine-grained-token"
printf '%s' "$content" | grep -Eq '(^|[^A-Z0-9])(AKIA|ASIA)[0-9A-Z]{16}([^A-Z0-9]|$)'           && hit "aws-access-key"
printf '%s' "$content" | grep -Eq 'xox[baprs]-[A-Za-z0-9-]{10,}'                               && hit "slack-token"
printf '%s' "$content" | grep -Eq 'AIza[0-9A-Za-z_-]{35}'                                      && hit "google-api-key"
printf '%s' "$content" | grep -Eq -- '-----BEGIN [A-Z ]*PRIVATE KEY( BLOCK)?-----'              && hit "private-key"
if [ "$in_fixture_zone" != true ]; then
  printf '%s' "$content" | grep -Eq 'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}' && hit "jwt-token"
fi

# ---- generic assignments: NAME_SECRET = "<long random value>" with a non-placeholder value ----
placeholder='(changeme|change-me|example|placeholder|your[-_ ]|<[^>]*>|\$\{|\$[A-Z_]+|xxx|dummy|replace|todo|\*{3,}|fake|sample|redacted|secret-here|insert|none|null|generate|openssl)'
while IFS= read -r line; do
  [ -z "$line" ] && continue
  val="$(printf '%s' "$line" | sed -E 's/^.*[=:][[:space:]]*["'"'"']?//; s/["'"'"'[:space:],;]*$//')"
  [ ${#val} -lt 24 ] && continue
  printf '%s' "$val" | grep -Eiq "$placeholder" && continue
  printf '%s' "$line" | grep -Eq '^[[:space:]]*#' && continue
  # must look random-ish: contains both letters and digits, no spaces
  if printf '%s' "$val" | grep -Eq '^[A-Za-z0-9+/_=.-]{24,}$' && printf '%s' "$val" | grep -Eq '[0-9]' && printf '%s' "$val" | grep -Eq '[A-Za-z]'; then
    hit "secret-assignment"
  fi
done < <(printf '%s' "$content" | grep -Ei '(SECRET|TOKEN|PASSWORD|PASSWD|PASSPHRASE|API[_-]?KEY|PRIVATE[_-]?KEY|APP[_-]?SECRET|ACCESS[_-]?KEY|CLIENT[_-]?SECRET)[A-Za-z0-9_]*[[:space:]]*[=:][[:space:]]*["'"'"']?[A-Za-z0-9+/_=.-]{24,}' || true)

# ---- card numbers: 13-19 digits (optionally grouped) passing Luhn ----
luhn_ok() {
  local n="$1" sum=0 alt=0 i d
  for (( i=${#n}-1; i>=0; i-- )); do
    d=${n:i:1}
    if (( alt )); then d=$((d*2)); (( d > 9 )) && d=$((d-9)); fi
    sum=$((sum+d)); alt=$((1-alt))
  done
  (( sum % 10 == 0 ))
}
while IFS= read -r cand; do
  [ -z "$cand" ] && continue
  digits="$(printf '%s' "$cand" | tr -dc '0-9')"
  len=${#digits}
  (( len < 13 || len > 19 )) && continue
  case "$digits" in 4111111111111111|4242424242424242|5555555555554444|378282246310005|4000000000000002) continue ;; esac   # public test PANs
  # skip obvious non-PAN numerics (all same digit, sequential test strings, timestamps)
  printf '%s' "$digits" | grep -Eq '^(.)\1+$' && continue
  if luhn_ok "$digits"; then hit "card-number(luhn)"; fi
done < <(printf '%s' "$content" | grep -oE '(^|[^0-9])([0-9][ -]?){12,18}[0-9]([^0-9]|$)' | sed -E 's/^[^0-9]//; s/[^0-9]$//' || true)

# ---- Egyptian mobile numbers outside the anonymized range 0100000000x / +2010000000xx ----
while IFS= read -r num; do
  [ -z "$num" ] && continue
  d="$(printf '%s' "$num" | tr -dc '0-9')"
  # normalize to local 11-digit form 01XXXXXXXXX
  case "$d" in 20*) d="0${d#20}" ;; 0020*) d="0${d#0020}" ;; esac
  [ ${#d} -ne 11 ] && continue
  case "$d" in 010000000[0-9][0-9]) continue ;; esac
  hit "egyptian-mobile-number (use the anonymized range +2010000000xx / 0100000000xx)"
done < <(printf '%s' "$content" | grep -oE '(\+20|0020|(^|[^0-9])0)1[0125][0-9]{8}([^0-9]|$)' || true)

exit 0
