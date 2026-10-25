#!/usr/bin/env bash
# rollback.sh [--dry-run]
#
# Redeploys the previous release on the single-VPS compose stack
# (`make rollback`; called automatically by release.yml when post-deploy smoke
# tests fail). Image tags are tracked in two env files that `make deploy-*`
# maintains:
#
#   infra/.deployed/current.env    tags of the release that is live now
#   infra/.deployed/previous.env   tags of the release before it
#
# Each file holds plain KEY=VALUE lines, e.g.
#   IMAGE_REGISTRY=ghcr.io/mohamedshehataa
#   BACKEND_IMAGE_TAG=1.4.2
#   WEB_IMAGE_TAG=1.4.2
#   DEPLOYED_AT=2026-10-23T18:04:11Z
#
# Rollback = load previous.env, `docker compose up -d` with those tags, then
# swap the two files so a second rollback goes back forward. Database schema is
# NOT rolled back here (Flyway migrations are forward-only; see docs/DEPLOY.md
# "Rollback" for the restore-from-backup path).
#
# Run on the VPS (or through `make rollback`, which ssh-es into it).
# Exit: 0 rolled back, 1 failure, 3 nothing to roll back to (missing files).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd "$here/.." && pwd)"
state_dir="${MASROOF_DEPLOY_STATE_DIR:-$infra_dir/.deployed}"
compose_file="${MASROOF_COMPOSE_FILE:-$infra_dir/docker-compose.yml}"
compose_project="${MASROOF_COMPOSE_PROJECT:-masroof}"
compose_overlay="${MASROOF_COMPOSE_OVERLAY:-}"   # e.g. docker-compose.prod.yml (production: overlay + .env next to it)
dry_run=0
[ "${1:-}" = "--dry-run" ] && dry_run=1

say() { printf 'rollback: %s\n' "$*"; }

explain_and_exit() {
  cat <<EOF
rollback: nothing to roll back to.

This script expects:
  $state_dir/previous.env   (missing: ${missing_prev})
  $state_dir/current.env    (missing: ${missing_cur})
  $compose_file             (missing: ${missing_compose})

What it will do once a deploy has run:
  1. Read BACKEND_IMAGE_TAG / WEB_IMAGE_TAG / IMAGE_REGISTRY from previous.env.
  2. docker compose -p $compose_project -f $compose_file up -d --no-build
     with those tags exported, pulling the images if needed.
  3. Wait for the backend health endpoint to report UP.
  4. Rotate current.env -> rolled-back-<timestamp>.env and previous.env -> current.env.
  5. Print the restored tags; 'make smoke URL=<public-url>' then verifies the site.

Until then, redeploy the previous tag manually (docs/DEPLOY.md, "Rollback") or
restore from the pre-deploy backup taken by 'make backup'.
EOF
  exit 3
}

missing_prev=no; missing_cur=no; missing_compose=no
[ -f "$state_dir/previous.env" ] || missing_prev=yes
[ -f "$state_dir/current.env" ] || missing_cur=yes
[ -f "$compose_file" ] || missing_compose=yes
if [ "$missing_prev" = yes ] || [ "$missing_compose" = yes ]; then
  explain_and_exit
fi

# Load only the keys we expect (never `source` an arbitrary file).
read_tag() { # read_tag <file> <KEY>
  grep -E "^$2=" "$1" | head -n 1 | cut -d= -f2- | tr -d '"' || true
}
prev_backend="$(read_tag "$state_dir/previous.env" BACKEND_IMAGE_TAG)"
prev_web="$(read_tag "$state_dir/previous.env" WEB_IMAGE_TAG)"
prev_registry="$(read_tag "$state_dir/previous.env" IMAGE_REGISTRY)"
[ -n "$prev_backend" ] || { say "previous.env has no BACKEND_IMAGE_TAG"; exit 1; }
[ -n "$prev_web" ] || prev_web="$prev_backend"

cur_backend="$(read_tag "$state_dir/current.env" BACKEND_IMAGE_TAG 2>/dev/null || true)"
say "current backend tag: ${cur_backend:-unknown}  ->  rolling back to: ${prev_backend} (web: ${prev_web})"

if [ "$dry_run" -eq 1 ]; then
  say "dry run: would run docker compose -p $compose_project -f $compose_file up -d --no-build"
  exit 0
fi

command -v docker >/dev/null 2>&1 || { say "docker is required on this host"; exit 1; }

dc() {
  local args=(-p "$compose_project" -f "$compose_file")
  [ -n "$compose_overlay" ] && args+=(-f "$compose_overlay")
  [ -f "$(dirname "$compose_file")/.env" ] && [ -n "$compose_overlay" ] && args+=(--env-file "$(dirname "$compose_file")/.env")
  docker compose "${args[@]}" "$@"
}

export IMAGE_REGISTRY="${prev_registry:-}"
export BACKEND_IMAGE_TAG="$prev_backend"
export WEB_IMAGE_TAG="$prev_web"

dc pull --quiet backend web 2>/dev/null || say "pull skipped (images may already be local)"
dc up -d --no-build --remove-orphans

# Wait for health (compose healthcheck or the local smoke script).
health_url="${MASROOF_HEALTH_URL:-http://localhost:8080/health}"
if ! SMOKE_TIMEOUT="${ROLLBACK_HEALTH_TIMEOUT:-60}" SMOKE_WEB_URL="${MASROOF_WEB_URL:-http://localhost:8080/}" \
     "$here/smoke.sh" "${health_url%/health}"; then
  say "backend did not become healthy after rollback; inspect: docker compose -p $compose_project logs --tail=200 backend"
  exit 1
fi

# Rotate state files.
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
if [ "$missing_cur" = no ]; then
  mv "$state_dir/current.env" "$state_dir/rolled-back-${stamp}.env"
fi
cp "$state_dir/previous.env" "$state_dir/current.env"
printf 'ROLLED_BACK_AT=%s\n' "$stamp" >> "$state_dir/current.env"
if [ -f "$state_dir/rolled-back-${stamp}.env" ]; then
  cp "$state_dir/rolled-back-${stamp}.env" "$state_dir/previous.env"
fi

say "done. live tags: backend=${BACKEND_IMAGE_TAG} web=${WEB_IMAGE_TAG} (state in $state_dir)"
say "next: make smoke URL=<public url>; then write docs/templates/incident.md -> docs/incidents/"
