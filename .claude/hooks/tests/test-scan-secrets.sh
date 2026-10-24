#!/usr/bin/env bash
# REQ-OPS-003: scan-secrets.sh blocks writes containing secret-like content.
# Fake secrets are assembled at runtime so this file never contains a secret-shaped literal.
# shellcheck source=lib-test.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib-test.sh"
H=scan-secrets.sh
F="$CLAUDE_PROJECT_DIR/backend/src/main/resources/application.yml"
rnd() { local n="$1" s=""; while [ ${#s} -lt "$n" ]; do s+="$(printf '%s' "$RANDOM$RANDOM" | tr '0-9' 'a-j')$RANDOM"; done; printf '%s' "${s:0:$n}"; }

ant="sk-ant-api03-$(rnd 48)"
oai="sk-proj-$(rnd 44)"
meta="EAAG$(rnd 80)"
ghp="ghp_$(rnd 36)"
aws="AKIA$(printf 'ABCDEFGHIJKLMNOP')"
pem="-----BEGIN RSA PRIVATE KEY-----"
jwt="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ.$(rnd 43)"
assign="JWT_SECRET=$(rnd 20)7a9b3c1d2e$(rnd 10)"

echo "-- vendor keys (REQ-OPS-003)"
run_hook $H "$(json_write "$F" "anthropic: $ant")";       assert_blocked "REQ-OPS-003 blocks Anthropic key";      assert_not_contains "$ERR" "$ant" "REQ-OPS-003 never echoes the secret back"
run_hook $H "$(json_write "$F" "key: $oai")";             assert_blocked "REQ-OPS-003 blocks OpenAI-style key"
run_hook $H "$(json_write "$F" "token=$meta")";           assert_blocked "REQ-OPS-003 blocks Meta access token"
run_hook $H "$(json_write "$F" "$ghp")";                  assert_blocked "REQ-OPS-003 blocks GitHub token"
run_hook $H "$(json_write "$F" "aws_access_key_id = $aws")"; assert_blocked "REQ-OPS-003 blocks AWS access key id"
run_hook $H "$(json_write "$F" "$pem
MIIEow...")";                                              assert_blocked "REQ-OPS-003 blocks private key block"
run_hook $H "$(json_write "$F" "Authorization: Bearer $jwt")"; assert_blocked "REQ-OPS-003 blocks JWT token in source"
run_hook $H "$(json_write "$CLAUDE_PROJECT_DIR/fixtures/whatsapp/payload.json" "{\"token\":\"$jwt\"}")"; assert_allowed "REQ-OPS-003 allows JWT-shaped strings in fixtures"

echo "-- generic secret assignments"
run_hook $H "$(json_write "$F" "$assign")";                               assert_blocked "REQ-OPS-003 blocks JWT_SECRET=<random>"
run_hook $H "$(json_write "$F" 'JWT_SECRET=changeme-generate-with-openssl-rand-base64-48')"; assert_allowed "REQ-OPS-003 allows changeme placeholder"
run_hook $H "$(json_write "$F" 'ANTHROPIC_API_KEY=')";                    assert_allowed "REQ-OPS-003 allows empty value"
# shellcheck disable=SC2016
run_hook $H "$(json_write "$F" 'WHATSAPP_APP_SECRET=${WHATSAPP_APP_SECRET}')"; assert_allowed "REQ-OPS-003 allows variable reference"
run_hook $H "$(json_write "$F" 'api_key: <your-anthropic-api-key-here-xxxxxxxx>')"; assert_allowed "REQ-OPS-003 allows angle-bracket placeholder"
run_hook $H "$(json_write "$F" 'password: example-password-for-docs-only-0000')"; assert_allowed "REQ-OPS-003 allows example placeholder"
run_hook $H "$(json_write "$F" '# TOKEN=abcdefghijklmnopqrstuvwxyz0123456789 (commented example)')"; assert_allowed "REQ-OPS-003 ignores commented lines"

echo "-- card numbers (Luhn)"
run_hook $H "$(json_write "$F" 'card: 5105105105105100')";                assert_blocked "REQ-OPS-003 blocks Luhn-valid 16-digit PAN"
run_hook $H "$(json_write "$F" 'card: 5105 1051 0510 5100')";             assert_blocked "REQ-OPS-003 blocks grouped PAN"
run_hook $H "$(json_write "$F" 'id: 1234567890123456')";                  assert_allowed "REQ-OPS-003 allows 16 digits failing Luhn"
run_hook $H "$(json_write "$F" 'card: 4242424242424242')";                assert_allowed "REQ-OPS-003 allows well-known public test PAN"
run_hook $H "$(json_write "$F" 'ending with ****1234 ref 20261023140501')"; assert_allowed "REQ-OPS-003 allows masked card + timestamp-like digits"
run_hook $H "$(json_write "$F" 'sha256: 3b4c8f2a9d1e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a')"; assert_allowed "REQ-OPS-003 allows hex digests"

echo "-- Egyptian mobile numbers"
run_hook $H "$(json_write "$F" 'phone: 01012345678')";                    assert_blocked "REQ-OPS-003 blocks a real-looking Egyptian mobile"
run_hook $H "$(json_write "$F" 'phone: +201112223334')";                  assert_blocked "REQ-OPS-003 blocks E.164 Egyptian mobile"
run_hook $H "$(json_write "$CLAUDE_PROJECT_DIR/fixtures/sms/x.jsonl" 'sender: 01512345678')"; assert_blocked "REQ-OPS-003 blocks real-looking numbers even in fixtures"
run_hook $H "$(json_write "$F" 'phone: 01000000012')";                    assert_allowed "REQ-OPS-003 allows anonymized range 0100000000xx"
run_hook $H "$(json_write "$F" 'phone: +2010000000 45')";                 assert_allowed "REQ-OPS-003 allows anonymized E.164 range"
run_hook $H "$(json_write "$F" 'ref 1234567890 at 20:10:12 amount 1,250.00')"; assert_allowed "REQ-OPS-003 allows ordinary numbers"

echo "-- Edit / MultiEdit carry content too"
run_hook $H "$(json_edit "$F" "x = '$ant'")";                             assert_blocked "REQ-OPS-003 blocks via Edit new_string"
run_hook $H "$(json_multiedit "$F" "y = '$ghp'")";                        assert_blocked "REQ-OPS-003 blocks via MultiEdit edits[]"
run_hook $H "$(json_write "$F" 'server:
  port: 8080
spring:
  datasource:
    url: ${POSTGRES_URL}')";                                              assert_allowed "REQ-OPS-003 allows ordinary config"
run_hook $H '{"hook_event_name":"PreToolUse","tool_name":"Write","tool_input":{"file_path":"x"}}'; assert_allowed "REQ-OPS-003 empty content passes"

echo "-- NotebookEdit new_source (STORY-001 verify F-14)"
run_hook $H "$(json_notebook "$CLAUDE_PROJECT_DIR/eval/notebooks/a.ipynb" "key = '$ant'")"; assert_blocked "REQ-OPS-003 blocks a key in NotebookEdit new_source"

echo "-- tolerant card / mobile formats and Arabic-Indic digits (F-14)"
# Numbers are assembled from short parts so this file itself never contains a PAN- or phone-shaped run.
# ar <ascii digits> -> the same number in Arabic-Indic digits (U+0660..U+0669), built at runtime (ASCII source)
ar() { local s="$1" o="" k ch b; for (( k = 0; k < ${#s}; k++ )); do ch="${s:k:1}"; case "$ch" in [0-9]) printf -v b "\\xd9\\xa$ch"; o+="$b" ;; *) o+="$ch" ;; esac; done; printf '%s' "$o"; }
# jq.exe on Windows reads env vars in the ANSI code page (non-ASCII becomes '?'), so UTF-8 content goes via stdin
json_write_utf8() { printf '%s' "$2" | J_P="$1" jq -Rs '{hook_event_name:"PreToolUse",tool_name:"Write",tool_input:{file_path:env.J_P,content:.},cwd:"x",session_id:"test"}'; }
g1=4539; g2=1488; g3=0343; g4=6467        # a Luhn-valid test-shaped PAN, in four groups
m1=010; m2=1234; m3=5678                  # local mobile parts (01x + 8 digits)
run_hook $H "$(json_write "$F" "card: $g1.$g2.$g3.$g4")";                 assert_blocked "REQ-OPS-003 blocks a dotted PAN"
run_hook $H "$(json_write "$F" "card: $g1  $g2  $g3  $g4")";              assert_blocked "REQ-OPS-003 blocks a PAN with double spaces"
run_hook $H "$(json_write_utf8 "$F" "card: $(ar "$g1$g2$g3$g4")")";            assert_blocked "REQ-OPS-003 blocks a PAN in Arabic-Indic digits"
run_hook $H "$(json_write "$F" "phone: +20 ${m1#0}1 ${m2:1} ${m2:0:1}${m3:0:3}")"; assert_blocked "REQ-OPS-003 blocks a spaced E.164 Egyptian mobile"
run_hook $H "$(json_write "$F" "phone: ${m1}1-${m2:1}-${m2:0:1}${m3:0:3}")"; assert_blocked "REQ-OPS-003 blocks a dashed Egyptian mobile"
run_hook $H "$(json_write_utf8 "$F" "phone: $(ar "$m1$m2$m3")")";              assert_blocked "REQ-OPS-003 blocks an Egyptian mobile in Arabic-Indic digits"
run_hook $H "$(json_write "$F" 'phone: +20 100 000 0045')";               assert_allowed "REQ-OPS-003 allows the spaced anonymized range"
run_hook $H "$(json_write "$CLAUDE_PROJECT_DIR/fixtures/sms/a.json" '{"received_at_ms": 1729700000006}')"; assert_allowed "REQ-OPS-003 13-digit epoch-ms timestamps are not PANs (min 14 digits)"

echo "-- 5 MB writes finish in < 2 s (REQ-OPS-003 unhappy path)"
# time_hook <json-file> -> RC, ERR and MS (wall-clock milliseconds of the hook alone).
# run.sh runs the suites in parallel, so a run over 2 s is measured once more and the faster run counts
# (machine load is not the hook's cost); RC/ERR must be the same in both runs.
time_hook() {
  local s e best=999999 rc1=""
  for _ in 1 2; do
    s="$EPOCHREALTIME"
    bash "$HOOKS_DIR/$H" < "$1" > /dev/null 2> "$TMP_ROOT/err.txt"; RC=$?
    e="$EPOCHREALTIME"
    MS="$(awk -v a="$s" -v b="$e" 'BEGIN { printf "%d", (b - a) * 1000 }')"
    (( MS < best )) && best="$MS"
    [ -n "$rc1" ] && [ "$rc1" != "$RC" ] && RC=99   # inconsistent verdicts fail the assertion
    rc1="$RC"
    (( best < 2000 )) && break
  done
  ERR="$(cat "$TMP_ROOT/err.txt")"
  MS="$best"
}
big="$(yes 'lorem ipsum dolor sit amet, consectetur adipiscing elit 2026-10-23 ref 1234 amount 1,250.00' | head -c 5000000 | tr '\n' ' ')"
printf '{"tool_name":"Write","tool_input":{"file_path":"%s","content":"%s"}}' "$F" "$big" > "$TMP_ROOT/clean.json"
printf '{"tool_name":"Write","tool_input":{"file_path":"%s","content":"%s %s"}}' "$F" "$big" "$ant" > "$TMP_ROOT/key.json"
nums="$(seq 1729700000000 1729700277777 | sed 's/^/ord /' | tr '\n' ' ' | head -c 5000000)"
printf '{"tool_name":"Write","tool_input":{"file_path":"%s","content":"%s"}}' "$F" "$nums" > "$TMP_ROOT/digits.json"
time_hook "$TMP_ROOT/clean.json";  assert_allowed "REQ-OPS-003 5 MB clean write passes"
assert_eq "$(( MS < 2000 ))" "1" "REQ-OPS-003 5 MB clean write scanned in < 2 s (took ${MS} ms)"
time_hook "$TMP_ROOT/key.json";    assert_blocked "REQ-OPS-003 5 MB write with a key at the end is blocked"
assert_contains "$ERR" "anthropic-api-key" "REQ-OPS-003 5 MB write names the detector"
assert_eq "$(( MS < 2000 ))" "1" "REQ-OPS-003 5 MB write with a key scanned in < 2 s (took ${MS} ms)"
time_hook "$TMP_ROOT/digits.json"; assert_allowed "REQ-OPS-003 5 MB of 13-digit numbers passes"
assert_eq "$(( MS < 2000 ))" "1" "REQ-OPS-003 5 MB of 13-digit numbers scanned in < 2 s (took ${MS} ms)"

echo "-- bounded PAN / mobile candidates and normalisation (STORY-001 loop 3, F-08..F-10)"
fw() { local s="$1" o="" k ch b; for (( k = 0; k < ${#s}; k++ )); do ch="${s:k:1}"; case "$ch" in [0-9]) printf -v b "\\xef\\xbc\\x9$ch"; o+="$b" ;; *) o+="$ch" ;; esac; done; printf '%s' "$o"; }
p1=5105; p2=1051; p3=0510; p4=5100      # a Luhn-valid PAN starting with 5, in four groups
nb=$'\xc2\xa0'
run_hook $H "$(json_write "$F" "card: $p1$p2$p3$p4")";                    assert_blocked "REQ-OPS-003 blocks a contiguous PAN starting with 5"
run_hook $H "$(json_write "$F" "card: $p1-$p2-$p3-$p4")";                 assert_blocked "REQ-OPS-003 blocks dddd-dddd-dddd-dddd"
run_hook $H "$(json_write "$F" "card: $p1.$p2.$p3.$p4")";                 assert_blocked "REQ-OPS-003 blocks dddd.dddd.dddd.dddd"
run_hook $H "$(json_write_utf8 "$F" "card: $p1$nb$p2$nb$p3$nb$p4")";       assert_blocked "REQ-OPS-003 blocks a PAN joined by NBSP"
run_hook $H "$(json_write "$F" "card: $p1	$p2	$p3	$p4")";               assert_blocked "REQ-OPS-003 blocks a PAN joined by tabs"
run_hook $H "$(json_write_utf8 "$F" "card: $(fw "$p1$p2$p3$p4")")";        assert_blocked "REQ-OPS-003 blocks a PAN in full-width digits"
run_hook $H "$(json_write "$F" "phone: ($m1) $m2-$m3")";                  assert_blocked "REQ-OPS-003 blocks (01x) xxxx-xxxx"
run_hook $H "$(json_write "$F" "t=1729700000000.123")";                   assert_allowed "REQ-OPS-003 an epoch-ms value with a fraction is not a PAN"
run_hook $H "$(json_write "$F" "id: 12345678901234567890123456789012")";  assert_allowed "REQ-OPS-003 a 32-digit digit-only UUID is not a PAN"
run_hook $H "$(json_write "$F" '```
ANTHROPIC_API_KEY=sk-ant-...
```')";                                                                   assert_allowed "REQ-OPS-003 sk-ant-... in a code fence is documentation"
RANDOM=42; recs=""; recs_sp=""
for i in $(seq 1 40); do
  a="$((RANDOM % 20000)).$((RANDOM % 90 + 10))"; b="$((RANDOM % 20000)).$((RANDOM % 90 + 10))"; c="$((RANDOM % 200)).$((RANDOM % 90 + 10))"
  recs+="$(printf '| 2026-10-%02d | spent %s %s %s EGP |' "$(( i % 28 + 1 ))" "$a" "$b" "$c")"$'\n'
  recs_sp+="$a $b $c"$'\n'
done
run_hook $H "$(json_write "$CLAUDE_PROJECT_DIR/docs/expenses.md" "$recs")";         assert_allowed "REQ-OPS-003 40 expense records with two-decimal amounts: 0 blocks"
run_hook $H "$(json_write "$CLAUDE_PROJECT_DIR/docs/expenses.md" "$recs_sp")";      assert_allowed "REQ-OPS-003 space-separated amount lists: 0 blocks"
run_hook $H "$(json_write_utf8 "$CLAUDE_PROJECT_DIR/docs/expenses.md" "$(ar "$recs")")"; assert_allowed "REQ-OPS-003 Arabic-Indic expense records: 0 blocks"
hex="$(printf '%08x%08x%08x%08x' "$RANDOM$RANDOM" "$RANDOM$RANDOM" "$RANDOM$RANDOM" "$RANDOM$RANDOM")"
long="$(yes secret | head -n 34000 | tr -d '\n')"
printf '{"tool_name":"Write","tool_input":{"file_path":"%s","content":"%s\\nWHATSAPP_APP_SECRET=%s"}}' "$F" "$long" "$hex" > "$TMP_ROOT/assign.json"
time_hook "$TMP_ROOT/assign.json"; assert_blocked "REQ-OPS-003 a 200 KB keyword line then WHATSAPP_APP_SECRET=<hex> is blocked"
assert_eq "$(( MS < 2000 ))" "1" "REQ-OPS-003 the 200 KB keyword line is scanned in < 2 s (took ${MS} ms)"
assert_not_contains "$ERR" "${hex:4:12}" "REQ-OPS-003 the app secret is not echoed"
sd="$(yes '1 2 3 4 5 6 7 8 9 0' | head -c 5000000 | tr '\n' ' ')"
printf '{"tool_name":"Write","tool_input":{"file_path":"%s","content":"%s"}}' "$F" "$sd" > "$TMP_ROOT/single.json"
time_hook "$TMP_ROOT/single.json"; assert_allowed "REQ-OPS-003 5 MB of space-separated single digits passes"
assert_eq "$(( MS < 2000 ))" "1" "REQ-OPS-003 5 MB of single digits scanned in < 2 s (took ${MS} ms)"

echo "-- bash fallback without Python"
export MASROOF_SCAN_NO_PYTHON=1
run_hook $H "$(json_write "$F" "anthropic: $ant")";                        assert_blocked "REQ-OPS-003 fallback blocks an Anthropic key"
run_hook $H "$(json_write "$F" "card: $g1 $g2 $g3 $g4")";                 assert_blocked "REQ-OPS-003 fallback blocks a grouped PAN"
run_hook $H "$(json_write "$F" "phone: $m1$m2$m3")";                      assert_blocked "REQ-OPS-003 fallback blocks an Egyptian mobile"
run_hook $H "$(json_notebook "$CLAUDE_PROJECT_DIR/eval/notebooks/a.ipynb" "key = '$ghp'")"; assert_blocked "REQ-OPS-003 fallback scans NotebookEdit new_source"
run_hook $H "$(json_write "$CLAUDE_PROJECT_DIR/fixtures/sms/a.json" '{"received_at_ms": 1729700000006}')"; assert_allowed "REQ-OPS-003 fallback ignores 13-digit timestamps"
run_hook $H "$(json_write "$F" 'server:
  port: 8080')";                                                          assert_allowed "REQ-OPS-003 fallback allows ordinary config"
unset MASROOF_SCAN_NO_PYTHON
finish
