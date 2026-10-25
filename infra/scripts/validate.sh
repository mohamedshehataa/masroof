#!/usr/bin/env bash
# Strict input validation shared by deploy.sh and backup.sh (sourced). Every value that reaches an ssh command line
# is checked here first, so a hostile VERSION/HOST/DOMAIN/IMAGE_REGISTRY can never become shell syntax or an ssh option.
# shellcheck shell=bash

die_input() { echo "deploy: $*" >&2; exit 2; }

# validate_version <v>: an image tag
validate_version() { [[ "$1" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die_input "VERSION must match ^[A-Za-z0-9._-]{1,64}\$"; }

# validate_registry <r>: lower-case host[:port][/path...]
validate_registry() {
  [[ "$1" =~ ^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?(/[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?){0,4}$ ]] \
    || die_input "IMAGE_REGISTRY must be a host[:port][/path] in lower case (e.g. ghcr.io/owner)"
}

# validate_domain <d>: a DNS name, no leading dash/dot
validate_domain() {
  [[ "$1" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$ ]] \
    || die_input "DOMAIN must be a host name such as masroof.duckdns.org"
}

# validate_host <[user@]host>: user = login name, host = IPv4 or DNS name; never starts with '-'
validate_host() {
  local u="" h="$1"
  case "$h" in *@*) u="${h%%@*}"; h="${h#*@}" ;; esac
  [ -z "$u" ] || [[ "$u" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || die_input "HOST user part is invalid"
  [[ "$h" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$ ]] || die_input "HOST must be an IP address or host name (no leading '-', no spaces or shell characters)"
}
