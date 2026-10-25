#!/usr/bin/env bash
# REQ: REQ-SEC-001
# Brings up the base stack (profile prod, so no dev switch) from a temp copy with a bootstrap admin and runs
# infra/scripts/auth-probe.sh through Caddy on http://localhost. Needs Docker, curl, jq and free host port 80.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
mkwork
cd "$WORK" || exit 1
pw="probe-$RANDOM-$RANDOM-phrase"
{
  echo "MASROOF_BOOTSTRAP_ADMIN_EMAIL=probe@example.com"
  echo "MASROOF_BOOTSTRAP_ADMIN_PASSWORD=$pw"
} >>.env

if ! dc up -d --build --wait >up.log 2>&1; then
  tail -n 25 up.log >&2
  dc logs --tail 40 backend 2>&1 | grep -iv "password\|secret" >&2
  fail "REQ-SEC-001_up: the base stack did not come up"
fi
if [ "$fails" -eq 0 ]; then
  PROBE_EMAIL=probe@example.com PROBE_PASSWORD="$pw" bash infra/scripts/auth-probe.sh http://localhost \
    || fail "REQ-SEC-001_probe: auth-probe.sh reported failures"
fi
finish req-sec-001-auth-probe
