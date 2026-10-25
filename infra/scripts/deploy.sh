#!/usr/bin/env bash
# deploy.sh <staging|prod> <version>   (via `make deploy-prod HOST=<ip> VERSION=<image tag> [DOMAIN=<name>]`)
#
# Single-VM deploy over ssh (Oracle Always Free, docs/HOSTING.md):
#   1. ship the compose files to ~/masroof/infra on the server (no secrets: the server's own .env holds them)
#   2. pull <registry>/masroof-{backend,web}:<version> and `compose up -d` (infra/oracle/remote-deploy.sh)
#   3. smoke the public URL (`make smoke URL=https://<domain>`)
#   4. smoke failed -> roll back to the previous tag on the server, smoke again, exit 1
#
# Env: HOST (ip or user@ip; user defaults to deploy; falls back to PROD_HOST), DOMAIN (falls back to PROD_DOMAIN),
#      IMAGE_REGISTRY (default ghcr.io/mohamedshehataa), SMOKE_TIMEOUT (default 180: first start issues the certificate),
#      DEPLOY_DRY_RUN=1 (print the plan, touch nothing), SSH_OPTS (extra ssh options),
#      MASROOF_SMOKE_SCRIPT (test hook).
# Only reachable through scripts/make/deploy.sh, which refuses unless /release is active. Prints no secrets.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

env_name="${1:?staging|prod}"
version="${2:-dev}"
version="${version#v}"
case "$env_name" in prod|staging) ;; *) echo "deploy: unknown environment '$env_name'" >&2; exit 2 ;; esac
host="${HOST:-${PROD_HOST:-}}"
domain="${DOMAIN:-${PROD_DOMAIN:-}}"
registry="$(printf '%s' "${IMAGE_REGISTRY:-ghcr.io/mohamedshehataa}" | tr '[:upper:]' '[:lower:]')"
smoke_script="${MASROOF_SMOKE_SCRIPT:-infra/scripts/smoke.sh}"

[ -n "$host" ] || { echo "deploy: HOST is required: make deploy-prod HOST=<ip> VERSION=<tag> DOMAIN=<name>.duckdns.org" >&2; exit 2; }
[ -n "$domain" ] || { echo "deploy: DOMAIN is required (the public name Caddy serves, e.g. masroof.duckdns.org)" >&2; exit 2; }
case "$version" in
  dev|"") echo "deploy: VERSION must be a published image tag (e.g. a release version or a main commit sha), not '$version'" >&2; exit 2 ;;
esac
case "$host" in *@*) ;; *) host="deploy@$host" ;; esac
case "$domain" in *[!A-Za-z0-9.-]*) echo "deploy: DOMAIN has invalid characters" >&2; exit 2 ;; esac

url="https://$domain"
echo "deploy: $env_name -> $domain (images $registry/masroof-{backend,web}:$version)"
if [ "${DEPLOY_DRY_RUN:-}" = "1" ]; then
  echo "deploy: dry run: would ship infra files, pull + up -d on the server, smoke $url, roll back on failure"
  exit 0
fi

# shellcheck disable=SC2206
ssh_opts=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 ${SSH_OPTS:-})
# shellcheck disable=SC2029  # the remote command strings are built client-side on purpose
rssh() { ssh "${ssh_opts[@]}" "$host" "$@"; }

tar -cf - -C infra docker-compose.yml docker-compose.prod.yml Caddyfile.prod scripts/rollback.sh scripts/smoke.sh \
  | rssh 'mkdir -p ~/masroof/infra && tar -xf - -C ~/masroof/infra'

rssh bash -s -- "$version" "$version" "$registry" < infra/oracle/remote-deploy.sh

if SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-180}" bash "$smoke_script" "$url"; then
  echo "deploy: smoke passed, $version is live"
  exit 0
fi

echo "deploy: smoke FAILED after deploying $version: rolling back to the previous tag" >&2
if rssh "MASROOF_COMPOSE_OVERLAY=docker-compose.prod.yml MASROOF_HEALTH_URL=$url/health MASROOF_WEB_URL=$url/ bash ~/masroof/infra/scripts/rollback.sh"; then
  if SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-180}" bash "$smoke_script" "$url"; then
    echo "deploy: rolled back; the previous release is healthy. $version was NOT released." >&2
  else
    echo "deploy: rolled back but smoke still failing: manual intervention required (docs/HOSTING.md, Troubleshooting)" >&2
  fi
else
  echo "deploy: rollback unavailable (first release, or the previous release also fails): inspect with 'make logs' on the server" >&2
fi
exit 1
