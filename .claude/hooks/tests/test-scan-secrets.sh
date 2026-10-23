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
finish
