#!/usr/bin/env bash
# make up|down|logs -> compose.sh (infra/docker-compose.yml + infra/docker-compose.local.yml: the single-household switch, no host ports)
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
cmd="${1:?up|down|logs}"
[ -f infra/docker-compose.yml ] || { echo "skip: infra/docker-compose.yml not present yet (Phase 1)"; exit 0; }
[ -f .env ] || { echo "no .env found - copying .env.example (fill in real values before anything external)"; cp .env.example .env; }
case "$cmd" in
  up)
    export GIT_SHA="${GIT_SHA:-$(git rev-parse HEAD | cut -c1-7)}"
    docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.local.yml up -d --build --wait || exit 1
    if [ -f infra/scripts/seed-demo.sh ]; then bash infra/scripts/seed-demo.sh; fi ;;
  down) if [ "${2:-}" = "1" ]; then docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.local.yml down -v; else docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.local.yml down; fi ;;
  logs) docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.local.yml logs -f --tail=200 ;;
esac
