#!/usr/bin/env bash
# REQ: REQ-OPS-020
# Compose-level checks for STORY-143: postgres + backend healthy, non-root, no published port in the base
# file, 127.0.0.1:8080 with the dev overlay, /health body, Accept ignored, Docker build needs GIT_SHA.
# Needs Docker, curl, jq and a .env with POSTGRES_PASSWORD. Run via `make e2e`.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

BASE=infra/docker-compose.yml
DEV=infra/docker-compose.dev.yml
ENVF=.env
[ -f "$ENVF" ] || ENVF=.env.example
fails=0
fail() { echo "FAIL REQ-OPS-020 $*" >&2; fails=$((fails + 1)); }
dc() { docker compose --env-file "$ENVF" -f "$BASE" "$@"; }
dcdev() { docker compose --env-file "$ENVF" -f "$BASE" -f "$DEV" "$@"; }

[ -f backend/Dockerfile ] || fail "REQ-OPS-020_dockerfile_exists: backend/Dockerfile is missing"
[ -f "$BASE" ] || fail "REQ-OPS-020_compose_exists: $BASE is missing"

sha=$(git rev-parse HEAD | cut -c1-7)
cleanup() { GIT_SHA="$sha" dcdev down >/dev/null 2>&1; docker image rm -f masroof/backend:gitsha-probe >/dev/null 2>&1; }
trap cleanup EXIT

req_ops_020_base_publishes_no_backend_port() {
  local ports
  ports=$(GIT_SHA="$sha" dc config --format json 2>/dev/null | jq -r '.services.backend.ports // [] | length')
  [ "$ports" = "0" ] || fail "REQ-OPS-020_base_no_published_port: backend ports under the base file = '$ports', want 0"
}

req_ops_020_healthchecks_defined() {
  local svc t
  for svc in postgres backend; do
    t=$(GIT_SHA="$sha" dcdev config --format json 2>/dev/null | jq -r --arg s "$svc" '.services[$s].healthcheck.test // empty | length')
    [ -n "$t" ] && [ "$t" != "0" ] || fail "REQ-OPS-020_healthcheck_$svc: no healthcheck.test for $svc"
  done
}

req_ops_020_up_healthy_nonroot() {
  if ! GIT_SHA="$sha" dcdev up -d --build --wait postgres backend; then
    fail "REQ-OPS-020_up: dev-overlay 'up -d --build --wait postgres backend' failed"
    return
  fi
  local ps svc state user
  ps=$(dcdev ps --format json 2>/dev/null | jq -s 'flatten')
  for svc in postgres backend; do
    state=$(echo "$ps" | jq -r --arg s "$svc" '.[] | select(.Service == $s) | .Health')
    [ "$state" = "healthy" ] || fail "REQ-OPS-020_healthy_$svc: health='$state', want healthy"
  done
  user=$(docker inspect -f '{{.Config.User}}' masroof/backend:dev 2>/dev/null)
  case "${user%%:*}" in
    "" | root | 0) fail "REQ-OPS-020_nonroot: Config.User='$user' is empty or root" ;;
  esac
}

req_ops_020_health_body() {
  local out hdr body want_version t_now t_resp
  out=$(curl -sS -m 5 -D - http://127.0.0.1:8080/health 2>&1) || { fail "REQ-OPS-020_health_reachable: curl failed on 127.0.0.1:8080/health"; return; }
  hdr=$(printf '%s' "$out" | tr -d '\r' | sed -n '1,/^$/p')
  body=$(printf '%s' "$out" | tr -d '\r' | sed '1,/^$/d')
  printf '%s' "$hdr" | head -1 | grep -q ' 200' || fail "REQ-OPS-020_health_200: status line '$(printf '%s' "$hdr" | head -1)'"
  printf '%s' "$hdr" | grep -qi '^content-type: application/json' || fail "REQ-OPS-020_health_content_type: not application/json"
  want_version=$(sed -n 's/^version = "\([^"]*\)".*/\1/p' backend/build.gradle.kts)
  [ "$(echo "$body" | jq -r .status)" = "UP" ] || fail "REQ-OPS-020_status: want UP"
  [ "$(echo "$body" | jq -r .version)" = "$want_version" ] || fail "REQ-OPS-020_version: want $want_version"
  echo "$body" | jq -er '.version | test("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$")' >/dev/null || fail "REQ-OPS-020_semver"
  [ "$(echo "$body" | jq -r .gitSha)" = "$sha" ] || fail "REQ-OPS-020_gitsha: want $sha"
  [ "$(echo "$body" | jq -c '.checks | keys')" = '["db"]' ] || fail "REQ-OPS-020_checks_keys: want [\"db\"]"
  [ "$(echo "$body" | jq -r .checks.db)" = "UP" ] || fail "REQ-OPS-020_checks_db: want UP"
  echo "$body" | jq -er '(.buildTime | endswith("Z")) and (.time | endswith("Z"))' >/dev/null || fail "REQ-OPS-020_times_z"
  t_now=$(date -u +%s)
  t_resp=$(date -u -d "$(echo "$body" | jq -r .time)" +%s 2>/dev/null || echo 0)
  [ $((t_now - t_resp)) -le 5 ] && [ $((t_resp - t_now)) -le 5 ] || fail "REQ-OPS-020_time_skew: time more than 5 s from now"
  [ "$(echo "$body" | jq -r .buildTime)" \< "$(echo "$body" | jq -r .time)" ] || fail "REQ-OPS-020_build_time_order: buildTime > time"
}

req_ops_020_accept_ignored() {
  local accept out
  for accept in application/xml text/html; do
    out=$(curl -sS -m 5 -o /dev/null -w '%{http_code} %{content_type}' -H "Accept: $accept" http://127.0.0.1:8080/health 2>&1)
    case "$out" in
      "200 application/json"*) ;;
      *) fail "REQ-OPS-020_accept_$accept: got '$out', want 200 application/json" ;;
    esac
  done
}

req_ops_020_gitsha_build_arg_required() {
  local args out rc
  for args in "NONE" "--build-arg GIT_SHA=" "--build-arg GIT_SHA=unknown"; do
    docker image rm -f masroof/backend:gitsha-probe >/dev/null 2>&1
    if [ "$args" = "NONE" ]; then
      out=$(docker build --progress=plain -f backend/Dockerfile -t masroof/backend:gitsha-probe . 2>&1)
    else
      # shellcheck disable=SC2086
      out=$(docker build --progress=plain -f backend/Dockerfile -t masroof/backend:gitsha-probe $args . 2>&1)
    fi
    rc=$?
    [ "$rc" -ne 0 ] || fail "REQ-OPS-020_gitsha_arg[$args]: build succeeded, want failure"
    printf '%s' "$out" | grep -q 'GIT_SHA' || fail "REQ-OPS-020_gitsha_arg[$args]: output does not name GIT_SHA"
    docker image inspect masroof/backend:gitsha-probe >/dev/null 2>&1 && fail "REQ-OPS-020_gitsha_arg[$args]: an image was produced"
  done
}

req_ops_020_base_publishes_no_backend_port
req_ops_020_healthchecks_defined
req_ops_020_up_healthy_nonroot
req_ops_020_health_body
req_ops_020_accept_ignored
req_ops_020_gitsha_build_arg_required

[ "$fails" -eq 0 ] && echo "ok REQ-OPS-020 backend compose" || exit 1
