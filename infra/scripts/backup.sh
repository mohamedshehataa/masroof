#!/usr/bin/env bash
# make backup HOST=<ip>: run the server's nightly backup job now (encrypted pg_dump on the VM, 7-day retention).
# Used by release.yml before a deploy. Skips with a clear message when no HOST is configured.
set -euo pipefail

host="${HOST:-${PROD_HOST:-}}"
if [ -z "$host" ]; then
  echo "backup: skip: HOST/PROD_HOST not set (no server to back up)"
  exit 0
fi
case "$host" in *@*) ;; *) host="deploy@$host" ;; esac
# shellcheck disable=SC2206
ssh_opts=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 ${SSH_OPTS:-})
# The job is root-owned (reads the passphrase, writes /var/backups): the deploy user may not run it; fall back to
# the systemd timer's last run when sudo is unavailable.
if ssh "${ssh_opts[@]}" "$host" 'sudo -n /usr/local/sbin/masroof-backup' 2>/dev/null; then
  exit 0
fi
echo "backup: sudo not available for $host; relying on the nightly timer (masroof-backup.timer)" >&2
exit 0
