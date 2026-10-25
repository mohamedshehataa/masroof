#!/usr/bin/env bash
# Runs ON the server (streamed over ssh by infra/scripts/deploy.sh):  bash -s -- <backend-tag> <web-tag> <registry>
# Records the previous release for rollback.sh, then pulls the images and brings the stack up.
set -euo pipefail

btag="${1:?backend tag}"; wtag="${2:?web tag}"; registry="${3:?registry}"
infra="$HOME/masroof/infra"
state="$infra/.deployed"
cd "$infra"
[ -f .env ] || { echo "remote-deploy: $infra/.env missing: run infra/oracle/bootstrap.sh first" >&2; exit 1; }
mkdir -p "$state"

if [ -f "$state/current.env" ]; then cp "$state/current.env" "$state/previous.env"; fi
{
  printf 'IMAGE_REGISTRY=%s\n' "$registry"
  printf 'BACKEND_IMAGE_TAG=%s\n' "$btag"
  printf 'WEB_IMAGE_TAG=%s\n' "$wtag"
  printf 'DEPLOYED_AT=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$state/current.env"

export IMAGE_REGISTRY="$registry" BACKEND_IMAGE_TAG="$btag" WEB_IMAGE_TAG="$wtag"
dc() { docker compose -p masroof -f docker-compose.yml -f docker-compose.prod.yml --env-file .env "$@"; }
dc pull --quiet backend web
dc up -d --no-build --remove-orphans
echo "remote-deploy: stack up (backend=$btag web=$wtag)"
