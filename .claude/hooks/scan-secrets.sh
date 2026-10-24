#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit|NotebookEdit): block writes whose content looks like a secret.
# Never echoes the matched secret back. Exit 2 = block.
# All detectors run in ONE Python process when Python is available (a 5 MB write must be scanned in
# < 2 s, and a PreToolUse hook that times out does not block). The bash path below is the fallback
# for machines without Python; its card scan is capped at 500 candidates.
HOOK_NAME=scan-secrets
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
# stdin goes to a temp file, not a bash variable: piping a 5 MB variable to python costs ~0.4 s
in_file="${TMPDIR:-/tmp}/masroof-scan-secrets.$$.json"   # outside the repo; removed on exit
trap 'rm -f "$in_file"' EXIT
cat > "$in_file" 2>/dev/null || { read_input; printf '%s' "$HOOK_INPUT" > "$in_file"; }
[ -s "$in_file" ] || exit 0

is_fixture_zone() {
  case "${1,,}" in fixtures/*|eval/datasets/*|*/src/test/*|*/__tests__/*|*.test.ts|*.test.tsx|*.spec.ts) return 0 ;; esac
  return 1
}
hit() { block "content for '$rel' matches secret pattern [$1] - never write real keys, tokens, private keys, card numbers or personal phone numbers; use .env (gitignored) and placeholders like 'changeme' or anonymized fixtures"; }

# ---------------- Python path (fast) ----------------
# stdout: line 1 = file path, then the names of the detectors that matched, in priority order.
# It stops at the first detector other than jwt-token (jwt is allowed in fixture zones, decided here).
# Keep the patterns in sync with the bash fallback below.
read -r -d '' SCAN_PY <<'PY'
import json, re, sys

raw = sys.stdin.buffer.read().decode("utf-8", "replace")
try:
    d = json.loads(raw)
except Exception:
    sys.exit(0)
ti = d.get("tool_input") or {}
if not isinstance(ti, dict):
    sys.exit(0)
parts = []
for k in ("content", "new_string", "new_source"):
    v = ti.get(k)
    if isinstance(v, str) and v:
        parts.append(v)
for e in ti.get("edits") or []:
    if isinstance(e, dict) and isinstance(e.get("new_string"), str):
        parts.append(e["new_string"])
fp = ti.get("file_path") or ti.get("notebook_path") or ""
out = [fp.replace("\n", " ")]
def emit():  # binary stdout: no CRLF translation on Windows
    sys.stdout.buffer.write(("\n".join(out) + "\n").encode("utf-8"))
text = "\n".join(parts)
if not text:
    emit()
    sys.exit(0)

VENDOR = [
    ("anthropic-api-key", r"sk-ant-[A-Za-z0-9_-]{20,}"),
    # lookbehinds instead of (^|[^...]) let the regex engine scan for the literal prefix (5x faster)
    ("openai-style-key", r"(?<![A-Za-z0-9])sk-(?:proj-|live-|test-)?[A-Za-z0-9_-]{32,}"),
    ("meta-access-token", r"(?<![A-Za-z0-9])EAA[A-Za-z0-9]{40,}"),
    ("github-token", r"gh[pousr]_[A-Za-z0-9]{36,}"),
    ("github-fine-grained-token", r"github_pat_[A-Za-z0-9_]{60,}"),
    ("aws-access-key", r"(?<![A-Z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}(?![A-Z0-9])"),
    ("slack-token", r"xox[baprs]-[A-Za-z0-9-]{10,}"),
    ("google-api-key", r"AIza[0-9A-Za-z_-]{35}"),
    ("private-key", r"-----BEGIN [A-Z ]*PRIVATE KEY(?: BLOCK)?-----"),
    ("jwt-token", r"eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
]
def found(name):
    out.append(name)
    if name != "jwt-token":
        emit()
        sys.exit(0)

for name, pat in VENDOR:
    if re.search(pat, text, re.M):
        found(name)

# generic NAME_SECRET = "<long random value>" (same rules as the bash fallback).
# Linear scan (STORY-001 loop 3, F-09): every '=' or ':' is looked at once; the name is read from at most
# 80 characters before it and the value with an anchored match after it, so a 200 KB line of keyword
# text costs O(length), not the O(length^2) of an unanchored NAME[A-Za-z0-9_]*= regex.
NAME_KEYS = ("SECRET", "TOKEN", "PASSWORD", "PASSWD", "PASSPHRASE", "APIKEY", "API_KEY", "PRIVATEKEY",
             "PRIVATE_KEY", "ACCESSKEY", "ACCESS_KEY")
NAME_END = re.compile(r"[A-Za-z0-9_-]+$")
VALUE = re.compile(r"[ \t]*[\"']?([A-Za-z0-9+/_=.-]{24,})")
PLACEHOLDER = re.compile(r"(changeme|change-me|example|placeholder|your[-_ ]|<[^>]*>|\$\{|\$[A-Z_]+|xxx|dummy|replace|todo|\*{3,}|fake|sample|redacted|secret-here|insert|none|null|generate|openssl|abcdefgh|0123456789)", re.I)
HAS_DIGIT = re.compile(r"[0-9]")
HAS_ALPHA = re.compile(r"[A-Za-z]")
KEYWORDS = ("secret", "token", "passw", "passphrase", "api_key", "api-key", "apikey", "private", "access")
low = text.lower()
lines = text.splitlines() if any(k in low for k in KEYWORDS) else []
for line in lines:
    ll = line.lower()
    if not any(k in ll for k in KEYWORDS) or line.lstrip(" \t").startswith("#"):
        continue
    i = -1
    while True:
        e, c = line.find("=", i + 1), line.find(":", i + 1)
        i = min(x for x in (e, c, len(line)) if x >= 0)
        if i >= len(line):
            break
        m = NAME_END.search(line[max(0, i - 80):i].rstrip(" \t"))
        if not m:
            continue
        name = m.group(0).upper().replace("-", "_")
        if not any(k in name for k in NAME_KEYS):
            continue
        v = VALUE.match(line, i + 1)
        if not v:
            continue
        val = v.group(1)
        if PLACEHOLDER.search(val) or not HAS_DIGIT.search(val) or not HAS_ALPHA.search(val):
            continue
        found("secret-assignment")

# card numbers and Egyptian mobiles (STORY-001 loop 3, REQ-OPS-003): NBSP, thin space, narrow NBSP and tab
# become a space; Arabic-Indic, Persian and full-width digits become ASCII. Candidates are bounded shapes
# only - lists of amounts or dates are never joined into one number.
NORM = {0xA0: 32, 0x2009: 32, 0x202F: 32, 9: 32}
for i in range(10):
    NORM[0x660 + i] = 48 + i
    NORM[0x6F0 + i] = 48 + i
    NORM[0xFF10 + i] = 48 + i
digits = text.translate(NORM)
TEST_PANS = {"4111111111111111", "4242424242424242", "5555555555554444", "378282246310005", "4000000000000002"}
def luhn(s):
    total = 0
    for i, ch in enumerate(reversed(s)):
        n = ord(ch) - 48
        if i % 2:
            n *= 2
            if n > 9:
                n -= 9
        total += n
    return total % 10 == 0
def mobile(s):
    if s.startswith("0020"):
        s = "0" + s[4:]
    elif s.startswith("20") and len(s) == 12:
        s = "0" + s[2:]
    if len(s) != 11 or not re.fullmatch(r"01[0125][0-9]{8}", s):
        return False
    return not re.fullmatch(r"010000000[0-9][0-9]", s)
# PAN candidates: (a) 14-19 contiguous digits, not touching a letter/digit and not part of a decimal;
# (b) dddd dddd dddd dddd [ddd] with one separator (space, '-' or '.'; two spaces tolerated) per gap,
# not continued by a further digit group on either side.
PAN_A = re.compile(r"(?<![\w.])\d{14,19}(?!\w)(?!\.\d)")
SEP = r"(?: {1,2}|[.-])"
PAN_B = re.compile(r"(?<![\w.-])(?<!\d )(?<!\d  )\d{4}(?:%s\d{4}){3}(?:%s\d{3})?(?!\w)(?!%s\d)" % (SEP, SEP, SEP))
# mobile candidates: optional +20/0020/20 country code, an optional (0xx) prefix, then groups of 2+ digits
# joined by one space or '-' (dots never join: they are decimals)
MOB = re.compile(r"(?<![\w+.-])(?:\+|00)?(?:\(\d{2,4}\)[ -]?|\d{2,})(?:[ -]\d{2,}){0,4}(?!\w)(?![.-]\d)")
card_hit = mobile_hit = False
for rx in (PAN_A, PAN_B):
    for m in rx.finditer(digits):
        acc = re.sub(r"\D", "", m.group(0))
        if acc[0] in "3456" and acc not in TEST_PANS and len(set(acc)) > 1 and luhn(acc):
            card_hit = True
            break
    if card_hit:
        break
for m in MOB.finditer(digits):
    s = m.group(0)
    acc = re.sub(r"\D", "", s)
    if not 11 <= len(acc) <= 14:
        continue
    intl = s.startswith(("+", "00")) or (acc.startswith("20") and len(acc) == 12)
    if not intl and not acc.startswith("0"):
        continue
    if mobile(acc):
        mobile_hit = True
        break
if card_hit:
    found("card-number(luhn)")
if mobile_hit:
    found("egyptian-mobile-number (use the anonymized range +2010000000xx / 0100000000xx)")
emit()
PY

if [ -z "${MASROOF_SCAN_NO_PYTHON:-}" ] && command -v "${PYTHON%% *}" >/dev/null 2>&1; then
  res=(); cr=$'\r'
  # $PYTHON may be "py -3" (two words), so it is intentionally unquoted
  # shellcheck disable=SC2086
  while IFS= read -r line; do line="${line%"$cr"}"; res+=("$line"); done < <($PYTHON -c "$SCAN_PY" < "$in_file" 2>/dev/null)
  if [ "${#res[@]}" -gt 0 ]; then
    rel="$(rel_to_project "${res[0]:-unknown}")"
    for det in "${res[@]:1}"; do
      [ "$det" = jwt-token ] && is_fixture_zone "$rel" && continue
      hit "$det"
    done
    exit 0
  fi
fi

# ---------------- bash fallback (no Python) ----------------
HOOK_INPUT="$(< "$in_file")"
content="$(json_get '.tool_input.content')"
[ -z "$content" ] && content="$(json_get '.tool_input.new_string')"
[ -z "$content" ] && content="$(json_get '.tool_input.new_source')"
if [ -z "$content" ] && have_jq; then
  content="$(printf '%s' "$HOOK_INPUT" | jq -r '[.tool_input.edits[]?.new_string] | join("\n")' 2>/dev/null)"
fi
[ -z "$content" ] && exit 0
fp="$(json_get '.tool_input.file_path')"
[ -z "$fp" ] && fp="$(json_get '.tool_input.notebook_path')"
rel="$(rel_to_project "${fp:-unknown}")"

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
if ! is_fixture_zone "$rel"; then
  printf '%s' "$content" | grep -Eq 'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}' && hit "jwt-token"
fi

# ---- generic secret assignments ----
placeholder='(changeme|change-me|example|placeholder|your[-_ ]|<[^>]*>|\$\{|\$[A-Z_]+|xxx|dummy|replace|todo|\*{3,}|fake|sample|redacted|secret-here|insert|none|null|generate|openssl|abcdefgh|0123456789)'
while IFS= read -r line; do
  [ -z "$line" ] && continue
  val="$(printf '%s' "$line" | sed -E 's/^.*[=:][[:space:]]*["'"'"']?//; s/["'"'"'[:space:],;]*$//')"
  [ ${#val} -lt 24 ] && continue
  printf '%s' "$val" | grep -Eiq "$placeholder" && continue
  printf '%s' "$line" | grep -Eq '^[[:space:]]*#' && continue
  if printf '%s' "$val" | grep -Eq '^[A-Za-z0-9+/_=.-]{24,}$' && printf '%s' "$val" | grep -Eq '[0-9]' && printf '%s' "$val" | grep -Eq '[A-Za-z]'; then
    hit "secret-assignment"
  fi
done < <(printf '%s' "$content" | grep -Ei '(SECRET|TOKEN|PASSWORD|PASSWD|PASSPHRASE|API[_-]?KEY|PRIVATE[_-]?KEY|APP[_-]?SECRET|ACCESS[_-]?KEY|CLIENT[_-]?SECRET)[A-Za-z0-9_]*[[:space:]]*[=:][[:space:]]*["'"'"']?[A-Za-z0-9+/_=.-]{24,}' || true)

# ---- card numbers: 14-19 digits (optionally grouped) passing Luhn; at most 500 candidates ----
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
  digits="${cand//[!0-9]/}"
  len=${#digits}
  (( len < 14 || len > 19 )) && continue
  case "$digits" in 4111111111111111|4242424242424242|5555555555554444|378282246310005|4000000000000002) continue ;; esac
  [[ "$digits" =~ ^(0+|1+|2+|3+|4+|5+|6+|7+|8+|9+)$ ]] && continue
  case "$digits" in [3456]*) ;; *) continue ;; esac
  if luhn_ok "$digits"; then hit "card-number(luhn)"; fi
done < <(printf '%s' "$content" | grep -oE '(^|[^0-9A-Za-z.])([0-9]{14,19}|[0-9]{4}(( {1,2}|[.-])[0-9]{4}){3}(( {1,2}|[.-])[0-9]{3})?)([^0-9A-Za-z]|$)' | head -n 500 || true)

# ---- Egyptian mobile numbers outside the anonymized range 0100000000x / +2010000000xx ----
while IFS= read -r num; do
  [ -z "$num" ] && continue
  d="${num//[!0-9]/}"
  case "$d" in 20*) d="0${d#20}" ;; 0020*) d="0${d#0020}" ;; esac
  [ ${#d} -ne 11 ] && continue
  case "$d" in 010000000[0-9][0-9]) continue ;; esac
  hit "egyptian-mobile-number (use the anonymized range +2010000000xx / 0100000000xx)"
done < <(printf '%s' "$content" | grep -oE '(\+20|0020|(^|[^0-9])0)1[0125]([ .-]?[0-9]){8}([^0-9]|$)' | head -n 500 || true)

exit 0
