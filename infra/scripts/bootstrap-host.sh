#!/usr/bin/env bash
# bootstrap-host.sh: one-time server setup over ssh (via `make bootstrap-host`, only inside /release).
#   HOST=<[user@]ip> DOMAIN=<name>.duckdns.org DUCKDNS_TOKEN_FILE=<local file with the token> bash infra/scripts/bootstrap-host.sh setup
#     -> installs the DuckDNS token (stdin, root-only file) and runs infra/host/bootstrap.sh with sudo.
#   HOST=... ADMIN_EMAIL=<email> ADMIN_PASSWORD_FILE=<local file> bash infra/scripts/bootstrap-host.sh admin-add
#     -> adds MASROOF_BOOTSTRAP_ADMIN_* to the server .env (values over stdin, never on a command line).
#        Password: 12-72 characters, at least 5 different ones, letters/digits/._~- only; the backend also refuses
#        repeated or sequential runs and common passwords (top-10k list) at first start.
#   HOST=... bash infra/scripts/bootstrap-host.sh admin-clear
#     -> removes those lines again once the first login exists (docs/HOSTING.md, First login).
#   HOST=... [SINCE=3h] bash infra/scripts/bootstrap-host.sh logs
#     -> read-only: backend WARN/ERROR lines through infra/scripts/mask_log.py, container status.
#   HOST=... bash infra/scripts/bootstrap-host.sh db-recent
#     -> read-only: state columns of the latest expenses (no amounts), raw messages (no body), refresh tokens (no values),
#        budget counts.
# Secrets are read from local files and streamed over stdin; nothing secret is printed or put in argv.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. infra/scripts/validate.sh

cmd="${1:?setup|admin-add|admin-clear|logs|db-recent}"
host="${HOST:-${PROD_HOST:-}}"
[ -n "$host" ] || die_input "HOST is required"
validate_host "$host"
case "$host" in *@*) ;; *) host="masroof-deploy@$host" ;; esac
hostkey="${SSH_STRICT_HOSTKEY:-accept-new}"
case "$hostkey" in accept-new|yes) ;; *) die_input "SSH_STRICT_HOSTKEY must be accept-new or yes" ;; esac
ssh_opts=(-o BatchMode=yes -o "StrictHostKeyChecking=$hostkey" -o ConnectTimeout=20)
# SSH_OPTS may only carry a key file (-i <file>) and a port (-p <n>): any -o could run local commands (ProxyCommand).
read -r -a extra <<< "${SSH_OPTS:-}"
i=0
while [ "$i" -lt "${#extra[@]}" ]; do
  case "${extra[$i]}" in
    -i) [ -f "${extra[$((i + 1))]:-}" ] || die_input "SSH_OPTS -i needs an existing key file"; ssh_opts+=(-i "${extra[$((i + 1))]}") ;;
    -p) [[ "${extra[$((i + 1))]:-}" =~ ^[0-9]{1,5}$ ]] || die_input "SSH_OPTS -p needs a port number"; ssh_opts+=(-p "${extra[$((i + 1))]}") ;;
    *) die_input "SSH_OPTS may only hold -i <key file> and -p <port>" ;;
  esac
  i=$((i + 2))
done

need_file() { # need_file <path> <what>
  [ -n "$1" ] && [ -f "$1" ] && [ -s "$1" ] || die_input "$2 must name a non-empty local file"
}

case "$cmd" in
  setup)
    domain="${DOMAIN:-${PROD_DOMAIN:-}}"
    validate_domain "$domain"
    sub="${domain%%.duckdns.org}"
    [ "$sub" != "$domain" ] || die_input "DOMAIN must be <name>.duckdns.org"
    need_file "${DUCKDNS_TOKEN_FILE:-}" DUCKDNS_TOKEN_FILE
    token="$(tr -d '\r\n' < "$DUCKDNS_TOKEN_FILE")"
    [[ "$token" =~ ^[A-Za-z0-9-]{20,64}$ ]] || die_input "DUCKDNS_TOKEN_FILE does not hold a DuckDNS token"
    printf 'DUCKDNS_TOKEN=%s\n' "$token" | ssh "${ssh_opts[@]}" -- "$host" \
      'sudo install -d -m 700 /etc/masroof && sudo install -m 600 /dev/stdin /etc/masroof/duckdns.env'
    # shellcheck disable=SC2029  # both values were validated above
    ssh "${ssh_opts[@]}" -- "$host" "sudo DOMAIN=$domain DUCKDNS_SUBDOMAIN=$sub bash -s" < infra/host/bootstrap.sh
    echo "bootstrap-host: $host set up for $domain" ;;
  admin-add)
    email="${ADMIN_EMAIL:-}"
    [[ "$email" =~ ^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}$ ]] || die_input "ADMIN_EMAIL is not an e-mail address"
    need_file "${ADMIN_PASSWORD_FILE:-}" ADMIN_PASSWORD_FILE
    pass="$(tr -d '\r\n' < "$ADMIN_PASSWORD_FILE")"
    [ "${#pass}" -ge 12 ] || die_input "the admin password must be at least 12 characters"
    [ "${#pass}" -le 72 ] || die_input "the admin password must be at most 72 bytes"
    [ "$(printf '%s' "$pass" | fold -w1 | sort -u | wc -l)" -ge 5 ] || die_input "the admin password needs at least 5 different characters"
    [[ "$pass" =~ ^[A-Za-z0-9._~-]+$ ]] || die_input "the admin password may only use letters, digits and . _ ~ -"
    printf 'MASROOF_BOOTSTRAP_ADMIN_EMAIL=%s\nMASROOF_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$email" "$pass" \
      | ssh "${ssh_opts[@]}" -- "$host" \
        'f=~/masroof/infra/.env; test -f "$f" || { echo "no server .env: run setup first" >&2; exit 1; }
         grep -v "^MASROOF_BOOTSTRAP_ADMIN_" "$f" > "$f.tmp" || true; cat >> "$f.tmp"; chmod 600 "$f.tmp"; mv "$f.tmp" "$f"'
    echo "bootstrap-host: bootstrap admin set in the server .env (remove with admin-clear after first start)" ;;
  admin-clear)
    ssh "${ssh_opts[@]}" -- "$host" \
      'f=~/masroof/infra/.env; grep -v "^MASROOF_BOOTSTRAP_ADMIN_" "$f" > "$f.tmp" || true; chmod 600 "$f.tmp"; mv "$f.tmp" "$f"'
    echo "bootstrap-host: bootstrap admin lines removed" ;;
  logs)
    # Read-only diagnostics: backend WARN/ERROR lines (plus "Caused by") only, never other levels, request headers or
    # bodies. Every line passes infra/scripts/mask_log.py (e-mails, phones, card/account digits incl. Arabic-Indic,
    # JWTs, long tokens) before it is printed; the mask is unit-tested (scripts/quality/tests/test_mask_log.py).
    since="${SINCE:-3h}"
    [[ "$since" =~ ^[0-9]{1,3}[mh]$ ]] || die_input "SINCE must look like 90m or 3h"
    # shellcheck disable=SC2029  # since was validated above
    ssh "${ssh_opts[@]}" -- "$host" "SINCE=$since bash -s" <<'REMOTE' | ${PYTHON:-python3} infra/scripts/mask_log.py
set -euo pipefail
backend="$(docker ps --format '{{.Names}}' | grep -E '^masroof-backend-[0-9]+$' | head -1)"
[ -n "$backend" ] || { echo "no backend container running" >&2; exit 1; }
echo "== $backend: WARN/ERROR since $SINCE =="
docker logs --since "$SINCE" "$backend" 2>&1 | grep -E ' (WARN|ERROR) |^Caused by:|"level":"(WARN|ERROR)"' | tail -n 150 || true
echo "== container status =="
docker ps --format '{{.Names}} {{.Status}}' | grep '^masroof-' || echo "no masroof containers"
REMOTE
    ;;
  db-recent)
    # Read-only diagnostics, in a read-only transaction: whether rows arrive and in what state. Never amounts, message
    # bodies, senders, notes, merchant text, token hashes or IP hashes. A column-granted read-only role is planned
    # (THREAT_MODEL B9).
    ssh "${ssh_opts[@]}" -- "$host" 'bash -s' <<'REMOTE'
set -euo pipefail
pg="$(docker ps --format '{{.Names}}' | grep -E '^masroof-postgres-[0-9]+$' | head -1)"
[ -n "$pg" ] || { echo "no postgres container running" >&2; exit 1; }
docker exec -i -e PGOPTIONS='-c default_transaction_read_only=on' "$pg" sh -c 'psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off' <<'SQL'
\echo == latest expenses ==
SELECT type, status, source, currency, occurred_at, created_at, deleted_at IS NOT NULL AS deleted,
       category_id IS NOT NULL AS has_category
  FROM expense ORDER BY created_at DESC LIMIT 15;
\echo == latest raw messages (no body) ==
SELECT source, processing_status, error_code, received_at, created_at, expense_id IS NOT NULL AS has_expense
  FROM raw_message ORDER BY created_at DESC LIMIT 10;
\echo == refresh tokens (no token values) ==
SELECT created_at, rotated_at, revoked_at, expires_at FROM refresh_token ORDER BY created_at DESC LIMIT 8;
\echo == budgets (counts) ==
SELECT period, currency, count(*) AS budgets FROM budget GROUP BY period, currency;
SQL
REMOTE
    ;;
  *) die_input "unknown command '$cmd'" ;;
esac
