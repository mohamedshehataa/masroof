#!/usr/bin/env bash
# REQ: REQ-OPS-022
# Unhappy paths on a temp copy: .env missing + no seed script, Postgres late at start-up, backend down.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

req_ops_022_env_missing_and_no_seed() {
  local out rc
  mkwork noenv
  out=$(cd "$WORK" && bash scripts/make/compose.sh up 2>&1)
  rc=$?
  printf '%s\n' "$out" | grep -q 'no .env found - copying .env.example' || fail "REQ-OPS-022_env_missing_msg: message not printed"
  [ -f "$WORK/.env" ] || fail "REQ-OPS-022_env_created: .env not created from .env.example"
  [ "$rc" -eq 0 ] || fail "REQ-OPS-022_no_seed_exit: up without seed-demo.sh exited $rc, want 0"
  dc down -v >/dev/null 2>&1
}

req_ops_022_postgres_late() {
  local t0 restart count rc=1
  mkwork
  dc build >/dev/null 2>&1 || fail "REQ-OPS-022_late_build: compose build failed"
  restart=$(dc config --format json | jq -r '.services.backend.restart // "no"')
  [ "$restart" != no ] || fail "REQ-OPS-022_restart_policy: backend restart='no', want a restart policy"
  dc up -d --no-deps backend >/dev/null 2>&1
  sleep 30 # scenario time: Postgres starts 30 s after the backend container
  count=$(docker inspect -f '{{.RestartCount}}' "$(dc ps -q backend)" 2>/dev/null || echo 0)
  [ "${count:-0}" -ge 1 ] || fail "REQ-OPS-022_backend_restarted: backend never exited and restarted while Postgres was down"
  dc up -d --wait postgres >/dev/null 2>&1
  t0=$(now)
  while [ $(($(now) - t0)) -le 90 ]; do
    [ "$(dc ps --format json 2>/dev/null | jq -s -r 'flatten | .[] | select(.Service == "backend") | .Health')" = healthy ] && { rc=0; break; }
    sleep 2
  done
  [ "$rc" -eq 0 ] || fail "REQ-OPS-022_backend_healthy_90s: backend not healthy 90 s after Postgres healthy"
  dc down -v >/dev/null 2>&1
}

req_ops_022_backend_down() {
  local out rc t0
  mkwork
  { dc build && (cd "$WORK" && bash scripts/make/compose.sh up); } >/dev/null 2>&1 || fail "REQ-OPS-022_down_setup: stack did not come up"
  dc stop backend >/dev/null 2>&1
  t0=$(now)
  out=$(SMOKE_TIMEOUT=10 bash "$WORK/infra/scripts/smoke.sh" http://localhost 2>&1)
  rc=$?
  [ "$rc" -ne 0 ] || fail "REQ-OPS-022_down_exit: smoke exited 0 with backend stopped"
  [ $(($(now) - t0)) -le 15 ] || fail "REQ-OPS-022_down_time: more than budget + 5 s"
  printf '%s\n' "$out" | grep -q '^FAIL health http://localhost/health last-status=502$' || fail "REQ-OPS-022_down_health_line: missing 'FAIL health http://localhost/health last-status=502'"
  printf '%s\n' "$out" | grep -q '^OK web-root ' || fail "REQ-OPS-022_down_webroot_ok: web-root should still print OK"
}

req_ops_022_env_missing_and_no_seed
req_ops_022_postgres_late
req_ops_022_backend_down
finish resilience
