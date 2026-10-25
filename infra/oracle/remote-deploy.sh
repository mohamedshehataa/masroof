#!/usr/bin/env bash
# Runs ON the server (streamed over ssh by infra/scripts/deploy.sh), fixed script, arguments pre-validated by the caller:
#   bash -s -- apply   <backend-tag> <web-tag> <registry>   pull + up -d the candidate; state files untouched
#   bash -s -- commit                                       candidate is healthy: current -> previous, candidate -> current
#   bash -s -- restore                                      candidate failed: bring the last healthy release (current.env) back
# State lives in ~/masroof/infra/.deployed: current.env = last healthy release, previous.env = the one before,
# candidate.env = release being deployed. Exit 3 from restore = nothing to restore (first release).
set -euo pipefail

cmd="${1:?apply|commit|restore}"
infra="$HOME/masroof/infra"
state="$infra/.deployed"
cd "$infra"
[ -f .env ] || { echo "remote-deploy: $infra/.env missing: run infra/oracle/bootstrap.sh first" >&2; exit 1; }
mkdir -p "$state"

dc() { docker compose -p masroof -f docker-compose.yml -f docker-compose.prod.yml --env-file .env "$@"; }
val() { grep -E "^$2=" "$1" | head -n 1 | cut -d= -f2- || true; }
up_with() { # up_with <registry> <backend-tag> <web-tag>
  export IMAGE_REGISTRY="$1" BACKEND_IMAGE_TAG="$2" WEB_IMAGE_TAG="$3"
  dc pull --quiet backend web
  dc up -d --no-build --remove-orphans
}

case "$cmd" in
  apply)
    btag="${2:?backend tag}"; wtag="${3:?web tag}"; registry="${4:?registry}"
    {
      printf 'IMAGE_REGISTRY=%s\n' "$registry"
      printf 'BACKEND_IMAGE_TAG=%s\n' "$btag"
      printf 'WEB_IMAGE_TAG=%s\n' "$wtag"
    } > "$state/candidate.env"
    up_with "$registry" "$btag" "$wtag"
    echo "remote-deploy: candidate up (backend=$btag web=$wtag)" ;;
  commit)
    [ -f "$state/candidate.env" ] || { echo "remote-deploy: no candidate to commit" >&2; exit 1; }
    stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if [ -f "$state/current.env" ] \
       && [ "$(val "$state/current.env" BACKEND_IMAGE_TAG)" = "$(val "$state/candidate.env" BACKEND_IMAGE_TAG)" ] \
       && [ "$(val "$state/current.env" WEB_IMAGE_TAG)" = "$(val "$state/candidate.env" WEB_IMAGE_TAG)" ]; then
      echo "remote-deploy: same tags as current: previous.env left alone"
    elif [ -f "$state/current.env" ]; then
      cp "$state/current.env" "$state/previous.env"
    fi
    { cat "$state/candidate.env"; printf 'DEPLOYED_AT=%s\n' "$stamp"; } > "$state/current.env"
    rm -f "$state/candidate.env"
    echo "remote-deploy: committed" ;;
  restore)
    rm -f "$state/candidate.env"
    [ -f "$state/current.env" ] || { echo "remote-deploy: no healthy release recorded; nothing to restore" >&2; exit 3; }
    up_with "$(val "$state/current.env" IMAGE_REGISTRY)" "$(val "$state/current.env" BACKEND_IMAGE_TAG)" "$(val "$state/current.env" WEB_IMAGE_TAG)"
    echo "remote-deploy: restored $(val "$state/current.env" BACKEND_IMAGE_TAG)" ;;
  *) echo "remote-deploy: unknown command '$cmd'" >&2; exit 2 ;;
esac
