#!/usr/bin/env bash
# smoke.sh <base-url>
#
# Post-deploy smoke test used by `make smoke URL=<url>` (S2 stage 9, S12). Two checks run concurrently:
#   health    GET <url>/health -> HTTP 200 and body contains "status":"UP"
#   web-root  GET <url>/       -> HTTP 200 and body contains <div id="root">
# Both share one SMOKE_TIMEOUT budget and retry inside it so a container that is still starting gets a fair chance.
# Output, one line per check: `OK <name> <url>` or `FAIL <name> <url> last-status=<code|000>` (000 = no connection).
#
# Env: SMOKE_TIMEOUT (seconds, default 10), SMOKE_HEALTH_PATH (default /health),
#      SMOKE_WEB_URL (default: <url>/; set when web is on another host).
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
health_url="${base}${SMOKE_HEALTH_PATH:-/health}"
web_url="${SMOKE_WEB_URL:-$base/}"
deadline=$(( $(date +%s) + timeout_s ))
out_dir="$(mktemp -d)"
trap 'rm -rf "$out_dir"' EXIT

# check <name> <url> <body-regex>: retries until OK or the deadline; writes its single result line to $out_dir/<name>.
check() {
  local name="$1" url="$2" re="$3" status="000" got body left
  while :; do
    left=$(( deadline - $(date +%s) ))
    [ "$left" -gt 0 ] || break
    body="$(curl -sS --max-time "$(( left < 5 ? left : 5 ))" -w '\n%{http_code}' "$url" 2>/dev/null || true)"
    got="$(printf '%s' "$body" | tail -n 1)"
    case "$got" in 000|*[!0-9]*) ;; *) status="$got" ;; esac   # keep the last status actually observed
    if [ "$status" = "200" ] && printf '%s' "$body" | grep -Eq "$re"; then
      echo "OK $name $url" >"$out_dir/$name"; return 0
    fi
    sleep 1
  done
  echo "FAIL $name $url last-status=$status" >"$out_dir/$name"
}

check health "$health_url" '"status"[[:space:]]*:[[:space:]]*"UP"' &
check web-root "$web_url" '<div id="root">' &
wait

cat "$out_dir/health" "$out_dir/web-root"
if grep -q '^FAIL' "$out_dir/health" "$out_dir/web-root"; then
  echo "smoke: FAILED" >&2
  exit 1
fi
echo "smoke: all checks passed"
