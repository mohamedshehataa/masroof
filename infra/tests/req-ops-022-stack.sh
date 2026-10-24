#!/usr/bin/env bash
# REQ: REQ-OPS-022
# Stack checks against `make up` (compose.sh up) from a temp copy: timing, health, gitSha, smoke, routing,
# published ports, non-root, no node in web. Needs Docker, curl, jq, free host port 80.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
mkwork
cd "$WORK" || exit 1

dc build >/dev/null 2>&1 || fail "REQ-OPS-022_build: 'docker compose build' failed (images must build before the timed up)"
t0=$(now)
bash scripts/make/compose.sh up >up.log 2>&1 || fail "REQ-OPS-022_up_exit: make up (compose.sh up) exited non-zero"
[ $(($(now) - t0)) -le 120 ] || fail "REQ-OPS-022_up_time: more than 120 s with prebuilt images"

req_ops_022_all_healthy() {
  local s h ps
  ps=$(dc ps --format json 2>/dev/null | jq -s 'flatten')
  for s in postgres minio backend web caddy; do
    h=$(echo "$ps" | jq -r --arg s "$s" '[.[] | select(.Service == $s) | .Health][0] // "absent"')
    [ "$h" = healthy ] || fail "REQ-OPS-022_healthy_$s: health='$h', want healthy"
  done
}
req_ops_022_gitsha_and_smoke() {
  local t out
  t=$(now)
  [ "$(curl -s -m 5 http://localhost/health | jq -r .gitSha 2>/dev/null)" = "$sha" ] || fail "REQ-OPS-022_gitsha: /health gitSha != $sha"
  out=$(SMOKE_TIMEOUT=10 bash infra/scripts/smoke.sh http://localhost 2>&1) || fail "REQ-OPS-022_smoke_exit: smoke exited non-zero"
  [ $(($(now) - t)) -le 15 ] || fail "REQ-OPS-022_smoke_time: more than budget + 5 s"
  printf '%s\n' "$out" | grep -q '^OK health ' || fail "REQ-OPS-022_smoke_health_ok: no 'OK health' line"
  printf '%s\n' "$out" | grep -q '^OK web-root ' || fail "REQ-OPS-022_smoke_webroot_ok: no 'OK web-root' line"
}
req_ops_022_routing() {
  local p want got
  # path|expected content-type substring; the first two go to the backend, the rest to the web container
  for p in "/health|json" "/api/does-not-exist|json" "/|text/html" "/some/client/route|text/html" "/actuator/health|text/html"; do
    want=${p#*|}
    got=$(curl -s -m 5 -o /dev/null -H 'Accept: */*' -w '%{http_code} %{content_type}' "http://localhost${p%|*}")
    case "$got" in *"$want"*) ;; *) fail "REQ-OPS-022_route[${p%|*}]: got '$got', want content-type containing $want" ;; esac
  done
  [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://localhost/health)" = 200 ] || fail "REQ-OPS-022_no_https_redirect: /health is not a direct 200"
}
req_ops_022_ports() {
  local ps pub port rc
  ps=$(dc ps --format json 2>/dev/null | jq -s 'flatten')
  pub=$(echo "$ps" | jq -r 'map(select(any(.Publishers[]?; .PublishedPort > 0)) | .Service) | unique | join(",")')
  [ "$pub" = caddy ] || fail "REQ-OPS-022_published_services: '$pub', want only caddy"
  port=$(echo "$ps" | jq -r '[.[] | select(.Service == "caddy") | .Publishers[]? | select(.PublishedPort > 0) | .PublishedPort] | unique | join(",")')
  [ "$port" = 80 ] || fail "REQ-OPS-022_caddy_port: caddy publishes '$port', want exactly 80 with DOMAIN empty"
  curl -s -m 3 -o /dev/null http://localhost:8080/health
  rc=$?
  [ "$rc" -eq 7 ] || fail "REQ-OPS-022_8080_refused: curl localhost:8080 rc=$rc, want 7 (connection refused)"
}
req_ops_022_images() {
  local s img u
  for s in backend web; do
    img=$(dc config --format json | jq -r --arg s "$s" '.services[$s].image // empty')
    u=$(docker inspect -f '{{.Config.User}}' "$img" 2>/dev/null)
    case "${u%%:*}" in "" | root | 0) fail "REQ-OPS-022_nonroot_$s: Config.User='$u' (image '$img')" ;; esac
  done
  img=$(dc config --format json | jq -r '.services.web.image // empty')
  if [ -z "$img" ] || docker run --rm --entrypoint node "$img" --version >/dev/null 2>&1; then
    fail "REQ-OPS-022_web_no_node: web image '$img' missing or still has a node runtime"
  fi
}
req_ops_022_all_healthy
req_ops_022_gitsha_and_smoke
req_ops_022_routing
req_ops_022_ports
req_ops_022_images
finish stack
