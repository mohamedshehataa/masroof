#!/usr/bin/env bash
# REQ: REQ-OPS-022
# No stack needed: smoke failure format against an unreachable host, and the missing-healthcheck detector
# (run on a planted compose file, then on the real one, which must define a healthcheck for all 5 services).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

req_ops_022_unreachable_host() {
  local out rc t0
  t0=$(now)
  out=$(bash "$REPO/infra/scripts/smoke.sh" http://localhost:9 2>&1)
  rc=$?
  [ "$rc" -eq 1 ] || fail "REQ-OPS-022_unreachable_exit: rc=$rc, want 1"
  [ $(($(now) - t0)) -le 15 ] || fail "REQ-OPS-022_unreachable_time: took more than 15 s"
  printf '%s\n' "$out" | grep -q '^FAIL health http://localhost:9/health last-status=000$' || fail "REQ-OPS-022_unreachable_health_line: missing 'FAIL health http://localhost:9/health last-status=000'"
  printf '%s\n' "$out" | grep -q '^FAIL web-root http://localhost:9/ last-status=000$' || fail "REQ-OPS-022_unreachable_webroot_line: missing 'FAIL web-root http://localhost:9/ last-status=000'"
}

req_ops_022_healthcheck_detector() {
  local d missing services
  d=$(mktemp -d)
  printf 'services:\n  okay:\n    image: alpine:3\n    healthcheck: {test: ["CMD", "true"]}\n  nohc:\n    image: alpine:3\n' >"$d/docker-compose.yml"
  missing=$(services_without_healthcheck "$d/docker-compose.yml")
  [ "$missing" = "nohc" ] || fail "REQ-OPS-022_detector_planted: want only 'nohc', got '$missing'"
  rm -rf "$d"
  export GIT_SHA=$sha POSTGRES_PASSWORD=x
  missing=$(cd "$REPO" && services_without_healthcheck infra/docker-compose.yml | tr '\n' ' ')
  [ -z "$missing" ] || fail "REQ-OPS-022_healthchecks_real: services without healthcheck: $missing"
  services=$(cd "$REPO" && docker compose -f infra/docker-compose.yml config --services | sort | tr '\n' ' ')
  [ "$services" = "backend caddy minio postgres web " ] || fail "REQ-OPS-022_services: got '$services', want backend caddy minio postgres web"
}

req_ops_022_unreachable_host
req_ops_022_healthcheck_detector
finish smoke-format
