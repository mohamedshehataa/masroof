#!/usr/bin/env bash
# make backup HOST=<ip>: run the server's backup job now (encrypted pg_dump + MinIO media on the VM, 7-day retention).
# Used by release.yml before a deploy. Skips with a clear message only when no HOST is configured; any failure of
# the remote job (exit code and stderr) is propagated.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. infra/scripts/validate.sh

host="${HOST:-${PROD_HOST:-}}"
if [ -z "$host" ]; then
  echo "backup: skip: HOST/PROD_HOST not set (no server to back up)"
  exit 0
fi
validate_host "$host"
case "$host" in *@*) ;; *) host="masroof-deploy@$host" ;; esac
# shellcheck disable=SC2206
ssh_opts=(-o BatchMode=yes -o "StrictHostKeyChecking=${SSH_STRICT_HOSTKEY:-accept-new}" -o ConnectTimeout=20 ${SSH_OPTS:-})
# The job is root-owned (reads the passphrase, writes /var/backups); the deploy user may run exactly this via sudo.
exec ssh "${ssh_opts[@]}" -- "$host" 'sudo -n /usr/local/sbin/masroof-backup'
