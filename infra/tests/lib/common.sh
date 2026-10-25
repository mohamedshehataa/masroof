#!/usr/bin/env bash
# REQ: REQ-OPS-022
# Shared helpers for infra/tests/req-ops-022-*.sh. Sourced, never run (it is not under infra/tests/*.sh).
# Hermetic: the stack runs from a temp copy of the working tree with its own .env, a unique compose project
# name and a trap that runs `down -v`. The real .env is never read or written; nothing prints secrets.
REPO=$(git rev-parse --show-toplevel) || exit 1
fails=0
fail() { echo "FAIL REQ-OPS-022 $*" >&2; fails=$((fails + 1)); }
finish() { [ "$fails" -eq 0 ] && echo "ok REQ-OPS-022 $1" || exit 1; }
sha=$(git -C "$REPO" rev-parse HEAD | cut -c1-7)
WORK=""
WORKS=()

# pick_net <env-file>: a free 172.28.<n>.0/24 for this throwaway project (CADDY_IP = .10), so parallel stacks never clash.
pick_net() {
  local n used
  used=$(docker network ls -q | xargs -r docker network inspect --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null)
  for _ in $(seq 1 50); do
    n=$((RANDOM % 200 + 20))
    case " $used " in *" 172.28.$n.0/24 "*) continue ;; esac
    printf 'MASROOF_NET_SUBNET=172.28.%s.0/24\nMASROOF_NET_RANGE=172.28.%s.128/25\nCADDY_IP=172.28.%s.10\n' "$n" "$n" "$n" >>"$1"
    return 0
  done
  echo "pick_net: no free subnet" >&2
  return 1
}

# mkwork [noenv]: temp copy of tracked+untracked files; .env from .env.example with DOMAIN empty unless noenv.
mkwork() {
  WORK=$(mktemp -d)
  WORKS+=("$WORK")
  export COMPOSE_PROJECT_NAME="masroof-t022-$$" GIT_SHA="$sha"
  (cd "$REPO" && git ls-files -co --exclude-standard -z | tar -c --null -T - 2>/dev/null) | tar -x -C "$WORK"
  rm -f "$WORK/.env" "$WORK/infra/scripts/seed-demo.sh"
  if [ "${1:-}" != noenv ]; then
    sed -e '/^DOMAIN=/d' -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=t022$RANDOM$RANDOM/" "$REPO/.env.example" >"$WORK/.env"
    echo "DOMAIN=" >>"$WORK/.env"
    sed -i "s|^MASROOF_JWT_SECRET=.*|MASROOF_JWT_SECRET=t022$(openssl rand -hex 24)|" "$WORK/.env"
    pick_net "$WORK/.env"
  fi
  trap cleanup_work EXIT
}
cleanup_work() {
  local w
  cd "$REPO" || true
  for w in "${WORKS[@]}"; do
    (cd "$w" && docker compose --env-file .env -f infra/docker-compose.yml down -v >/dev/null 2>&1)
    rm -rf "$w"
  done
}
dc() { (cd "$WORK" && docker compose --env-file .env -f infra/docker-compose.yml "$@"); }
now() { date +%s; }

# services_without_healthcheck <compose-file>: prints one service name per line.
services_without_healthcheck() {
  docker compose -f "$1" config --format json | jq -r '.services | to_entries[] | select((.value.healthcheck.test // []) | length == 0) | .key'
}
