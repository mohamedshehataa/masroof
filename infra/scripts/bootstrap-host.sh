#!/usr/bin/env bash
# bootstrap-host.sh: one-time server setup over ssh (via `make bootstrap-host`, only inside /release).
#   HOST=<[user@]ip> DOMAIN=<name>.duckdns.org DUCKDNS_TOKEN_FILE=<local file with the token> bash infra/scripts/bootstrap-host.sh setup
#     -> installs the DuckDNS token (stdin, root-only file) and runs infra/host/bootstrap.sh with sudo.
#   HOST=... ADMIN_EMAIL=<email> ADMIN_PASSWORD_FILE=<local file> bash infra/scripts/bootstrap-host.sh admin-add
#     -> adds MASROOF_BOOTSTRAP_ADMIN_* to the server .env (values over stdin, never on a command line).
#   HOST=... bash infra/scripts/bootstrap-host.sh admin-clear
#     -> removes those lines again once the first login exists (docs/HOSTING.md, First login).
# Secrets are read from local files and streamed over stdin; nothing secret is printed or put in argv.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. infra/scripts/validate.sh

cmd="${1:?setup|admin-add|admin-clear}"
host="${HOST:-${PROD_HOST:-}}"
[ -n "$host" ] || die_input "HOST is required"
validate_host "$host"
case "$host" in *@*) ;; *) host="masroof-deploy@$host" ;; esac
# shellcheck disable=SC2206
ssh_opts=(-o BatchMode=yes -o "StrictHostKeyChecking=${SSH_STRICT_HOSTKEY:-accept-new}" -o ConnectTimeout=20 ${SSH_OPTS:-})

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
  *) die_input "unknown command '$cmd'" ;;
esac
