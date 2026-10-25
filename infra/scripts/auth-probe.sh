#!/usr/bin/env bash
# auth-probe.sh <base-url>   (REQ-SEC-001/005/006/012)
#
# curl probe of the login API against a running stack (`make up`, or any deployment). Needs curl and jq.
# Env: PROBE_EMAIL and PROBE_PASSWORD, an existing login (for example the bootstrap admin).
#      PROBE_EXPECT_ANON=401 (default) when anonymous requests must be refused; set PROBE_EXPECT_ANON=any for
#      `make up`, whose overlay turns on the single-household dev switch (anonymous calls then reach the dev household).
# Never prints tokens or the password. Exit 0 = every probe passed, 1 = a probe failed, 2 = usage.
set -uo pipefail

[ $# -eq 1 ] || { echo "usage: $(basename "$0") <base-url>" >&2; exit 2; }
base="${1%/}"
: "${PROBE_EMAIL:?set PROBE_EMAIL}" "${PROBE_PASSWORD:?set PROBE_PASSWORD}"
anon="${PROBE_EXPECT_ANON:-401}"
fails=0
ok() { echo "OK   $1"; }
bad() { echo "FAIL $1" >&2; fails=$((fails + 1)); }
# req <name> <want-status> <curl args...>: body -> $body, headers -> $hdr
req() {
  local name=$1 want=$2
  shift 2
  local out
  out=$(curl -s -m 10 -D - -w '\n%{http_code}' "$@") || { bad "$name: curl failed"; body=""; hdr=""; return 1; }
  code=${out##*$'\n'}
  out=${out%$'\n'*}
  hdr=$(printf '%s' "$out" | tr -d '\r' | sed -n '1,/^$/p')
  body=$(printf '%s' "$out" | tr -d '\r' | sed '1,/^$/d')
  if [ "$want" = any ] || [ "$code" = "$want" ]; then ok "$name -> $code"; else bad "$name: got $code, want $want"; return 1; fi
}

# an XFF per run keeps repeated probes from sharing the per-IP failure budget
xff=(-H "X-Forwarded-For: 198.51.100.$((RANDOM % 250 + 1))")

req "health is public" 200 "$base/health"

login=$(jq -nc --arg e "$PROBE_EMAIL" --arg p "$PROBE_PASSWORD" '{email:$e,password:$p}')
req "login" 200 "${xff[@]}" -H 'Content-Type: application/json' -d "$login" "$base/auth/login"
access=$(printf '%s' "$body" | jq -r .accessToken)
refresh=$(printf '%s' "$body" | jq -r .refreshToken)
[ "$(printf '%s' "$body" | jq -r .tokenType)" = Bearer ] && [ "$(printf '%s' "$body" | jq -r .expiresIn)" -le 900 ] || bad "login: tokenType/expiresIn"

wrong=$(jq -nc --arg e "$PROBE_EMAIL" '{email:$e,password:"definitely-not-the-password"}')
req "login with a wrong password" 401 "${xff[@]}" -H 'Content-Type: application/json' -d "$wrong" "$base/auth/login"
printf '%s' "$hdr" | grep -qi '^content-type: application/problem+json' || bad "wrong password: not problem+json"

req "authenticated call" 200 -H "Authorization: Bearer $access" "$base/api/categories"
printf '%s' "$hdr" | grep -qi '^x-content-type-options: nosniff' || bad "missing X-Content-Type-Options"
req "garbage bearer" 401 -H 'Authorization: Bearer not.a.jwt' "$base/api/categories"
printf '%s' "$hdr" | grep -qi '^www-authenticate: bearer' || bad "401 without WWW-Authenticate"
none="eyJhbGciOiJub25lIn0.$(printf '%s' '{"sub":"x","hid":"x","exp":4102444800}' | base64 | tr -d '=\n' | tr '+/' '-_')."
req "alg=none token" 401 -H "Authorization: Bearer $none" "$base/api/categories"
if [ "$anon" = 401 ]; then
  req "anonymous call" 401 "$base/api/categories"
else
  req "anonymous call (dev switch)" any "$base/api/categories"
fi

req "refresh" 200 -H 'Content-Type: application/json' -d "$(jq -nc --arg t "$refresh" '{refreshToken:$t}')" "$base/auth/refresh"
refresh2=$(printf '%s' "$body" | jq -r .refreshToken)
req "replay of the rotated token" 401 -H 'Content-Type: application/json' -d "$(jq -nc --arg t "$refresh" '{refreshToken:$t}')" "$base/auth/refresh"
req "successor dead after reuse" 401 -H 'Content-Type: application/json' -d "$(jq -nc --arg t "$refresh2" '{refreshToken:$t}')" "$base/auth/refresh"

req "login again" 200 "${xff[@]}" -H 'Content-Type: application/json' -d "$login" "$base/auth/login"
access=$(printf '%s' "$body" | jq -r .accessToken)
refresh=$(printf '%s' "$body" | jq -r .refreshToken)
req "logout" 204 -H "Authorization: Bearer $access" -H 'Content-Type: application/json' -d "$(jq -nc --arg t "$refresh" '{refreshToken:$t}')" "$base/auth/logout"
req "refresh after logout" 401 -H 'Content-Type: application/json' -d "$(jq -nc --arg t "$refresh" '{refreshToken:$t}')" "$base/auth/refresh"

[ "$fails" -eq 0 ] && { echo "auth-probe: all probes passed"; exit 0; }
echo "auth-probe: $fails probe(s) failed" >&2
exit 1
