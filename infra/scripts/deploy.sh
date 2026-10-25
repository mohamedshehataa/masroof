#!/usr/bin/env bash
# deploy.sh <staging|prod> <version>   (via `make deploy-prod HOST=<ip> VERSION=<image tag> [DOMAIN=<name>]`)
#
# Single-VM deploy over ssh (Oracle Always Free, docs/HOSTING.md):
#   1. ship the compose files to ~/masroof/infra on the server (no secrets: the server's own .env holds them)
#   2. `apply`: pull <registry>/masroof-{backend,web}:<version> and `compose up -d` (infra/oracle/remote-deploy.sh)
#   3. smoke the public URL (`make smoke URL=https://<domain>`)
#   4. a failing apply (pull/up, e.g. unhealthy backend) OR a failing smoke -> `restore` the last healthy release,
#      smoke again, exit 1. Only a healthy candidate is `commit`ted to current.env/previous.env.
#
# Env: HOST (ip or [user@]host; user defaults to deploy; falls back to PROD_HOST), DOMAIN (falls back to PROD_DOMAIN),
#      IMAGE_REGISTRY (default ghcr.io/mohamedshehataa), SMOKE_TIMEOUT (default 180: first start issues the certificate),
#      DEPLOY_DRY_RUN=1 (validate and print the plan, touch nothing), SSH_OPTS (extra ssh options),
#      SSH_STRICT_HOSTKEY=yes (refuse unknown host keys; default accept-new), MASROOF_SMOKE_SCRIPT (test hook).
# Only reachable through scripts/make/deploy.sh, which refuses unless /release is active. Prints no secrets.
# Every input is validated (validate.sh) before it can reach a command line; remote arguments are also %q-quoted.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. infra/scripts/validate.sh

env_name="${1:?staging|prod}"
version="${2:-}"
version="${version#v}"
case "$env_name" in prod|staging) ;; *) die_input "unknown environment '$env_name'" ;; esac
host="${HOST:-${PROD_HOST:-}}"
domain="${DOMAIN:-${PROD_DOMAIN:-}}"
registry="$(printf '%s' "${IMAGE_REGISTRY:-ghcr.io/mohamedshehataa}" | tr '[:upper:]' '[:lower:]')"
smoke_script="${MASROOF_SMOKE_SCRIPT:-infra/scripts/smoke.sh}"

[ -n "$host" ] || die_input "HOST is required: make deploy-prod HOST=<ip> VERSION=<tag> DOMAIN=<name>.duckdns.org"
[ -n "$domain" ] || die_input "DOMAIN is required (the public name Caddy serves, e.g. masroof.duckdns.org)"
case "$version" in dev|"") die_input "VERSION must be a published image tag (a release version or a main commit sha), not '$version'" ;; esac
validate_version "$version"
validate_registry "$registry"
validate_domain "$domain"
validate_host "$host"
case "$host" in *@*) ;; *) host="deploy@$host" ;; esac

url="https://$domain"
echo "deploy: $env_name -> $domain (images $registry/masroof-{backend,web}:$version)"
if [ "${DEPLOY_DRY_RUN:-}" = "1" ]; then
  echo "deploy: dry run: would ship infra files, apply + smoke $url, commit when healthy, restore the last healthy release otherwise"
  exit 0
fi

# shellcheck disable=SC2206
ssh_opts=(-o BatchMode=yes -o "StrictHostKeyChecking=${SSH_STRICT_HOSTKEY:-accept-new}" -o ConnectTimeout=20 ${SSH_OPTS:-})
# remote <subcommand> [args...]: runs the fixed remote-deploy.sh with %q-quoted arguments ("--" ends ssh options).
remote() {
  local a="" w
  for w in "$@"; do a="$a $(printf '%q' "$w")"; done
  # shellcheck disable=SC2029  # the command string is built client-side on purpose; every word is %q-quoted
  ssh "${ssh_opts[@]}" -- "$host" "bash -s --$a" < infra/oracle/remote-deploy.sh
}
smoke() { SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-180}" bash "$smoke_script" "$url"; }

tar -cf - -C infra docker-compose.yml docker-compose.prod.yml Caddyfile.prod scripts/rollback.sh scripts/smoke.sh \
  | ssh "${ssh_opts[@]}" -- "$host" 'mkdir -p ~/masroof/infra && tar -xf - -C ~/masroof/infra'

if remote apply "$version" "$version" "$registry" && smoke; then
  remote commit
  echo "deploy: smoke passed, $version is live"
  exit 0
fi

echo "deploy: $version did not come up healthy (pull/up or smoke failed): restoring the last healthy release" >&2
rc=0
remote restore || rc=$?
if [ "$rc" -eq 0 ]; then
  if smoke; then
    echo "deploy: restored; the previous release is healthy. $version was NOT released." >&2
  else
    echo "deploy: restored but smoke still failing: manual intervention required (docs/HOSTING.md, Troubleshooting)" >&2
  fi
elif [ "$rc" -eq 3 ]; then
  echo "deploy: first release, nothing to restore: inspect with 'docker compose -p masroof logs' on the server" >&2
else
  echo "deploy: restore failed (exit $rc): manual intervention required" >&2
fi
exit 1
