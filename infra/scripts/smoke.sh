#!/usr/bin/env bash
# smoke.sh <base-url>
#
# Post-deploy smoke test used by `make smoke URL=<url>` (S2 stage 9, S12).
#   1. GET <url>/health      -> HTTP 200 and body contains "UP" (Spring Actuator style)
#   2. GET <url>/            -> HTTP 200 (web root / PWA shell)
# Whole run is bounded to SMOKE_TIMEOUT seconds (default 10). Retries inside the
# budget so a container that is still starting gets a fair chance.
#
# Env: SMOKE_TIMEOUT (seconds, default 10), SMOKE_HEALTH_PATH (default /health),
#      SMOKE_WEB_URL (default: same origin as <url>; set when web is on another host).
# Exit: 0 all checks passed, 1 a check failed, 2 usage error.
set -euo pipefail

usage() { echo "usage: $(basename "$0") <base-url>   e.g. $(basename "$0") https://masroof.example.com" >&2; exit 2; }
[ $# -eq 1 ] || usage
command -v curl >/dev/null 2>&1 || { echo "smoke: curl is required" >&2; exit 2; }

base="${1%/}"
case "$base" in
  http://*|https://*) ;;
  *) echo "smoke: URL must start with http:// or https://: $base" >&2; exit 2 ;;
esac

timeout_s="${SMOKE_TIMEOUT:-10}"
health_path="${SMOKE_HEALTH_PATH:-/health}"
web_url="${SMOKE_WEB_URL:-$base/}"
deadline=$(( $(date +%s) + timeout_s ))
failures=0

remaining() { local r=$(( deadline - $(date +%s) )); [ "$r" -gt 0 ] && echo "$r" || echo 0; }

# fetch <url> -> prints "<status>\t<body-first-200-chars>" ; never exits the script
fetch() {
  local url="$1" max="$2" out status body
  out="$(curl -sS --max-time "$max" --location --write-out '\n%{http_code}' "$url" 2>/dev/null || true)"
  status="$(printf '%s' "$out" | tail -n 1)"
  body="$(printf '%s' "$out" | sed '$d' | tr -d '\r\n' | cut -c1-200)"
  printf '%s\t%s\n' "${status:-000}" "$body"
}

# check <name> <url> <expected-body-substring or ''>
check() {
  local name="$1" url="$2" expect="$3" res status body
  while :; do
    local left; left="$(remaining)"
    if [ "$left" -le 0 ]; then
      echo "FAIL  $name  $url  (timed out after ${timeout_s}s)"
      failures=$((failures + 1)); return
    fi
    local per=$(( left < 5 ? left : 5 ))
    res="$(fetch "$url" "$per")"
    status="${res%%	*}"; body="${res#*	}"
    if [ "$status" = "200" ] && { [ -z "$expect" ] || [[ "$body" == *"$expect"* ]]; }; then
      echo "OK    $name  $url  (200${expect:+, body contains \"$expect\"})"
      return
    fi
    sleep 1
  done
}

echo "smoke: $base (budget ${timeout_s}s)"
check "health" "${base}${health_path}" "UP"
check "web root" "$web_url" ""

if [ "$failures" -gt 0 ]; then
  echo "smoke: ${failures} check(s) failed" >&2
  exit 1
fi
echo "smoke: all checks passed"
