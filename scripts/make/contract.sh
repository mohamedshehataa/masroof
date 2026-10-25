#!/usr/bin/env bash
# make contract-lint -> contract.sh lint   (redocly, pinned major 2)
# Resolution order: ./node_modules/.bin/redocly, <repo>/node_modules/.bin/redocly, then ONE shared install under
# $MASROOF_TOOLS_DIR (default ~/.cache/masroof-tools), created under a mkdir lock. No `npx`: parallel callers
# (the quality tests lint from temp dirs) raced on ~/.npm/_npx (ENOTEMPTY).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
[ -f contracts/openapi.yaml ] || { echo "skip: contracts/openapi.yaml not present"; exit 0; }

REDOCLY_VERSION=2
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

shared_redocly() {
  local dir="${MASROOF_TOOLS_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/masroof-tools}"
  local bin="$dir/node_modules/.bin/redocly" lock="$dir.lock" waited=0
  if [ ! -x "$bin" ]; then
    mkdir -p "$(dirname "$dir")"
    until mkdir "$lock" 2>/dev/null; do
      [ -x "$bin" ] && break
      waited=$((waited + 1))
      [ "$waited" -gt 300 ] && { echo "redocly install lock timeout ($lock)" >&2; return 1; }
      sleep 1
    done
    if [ ! -x "$bin" ]; then
      mkdir -p "$dir"
      npm install --prefix "$dir" --no-audit --no-fund --silent "@redocly/cli@$REDOCLY_VERSION" >&2 || { rmdir "$lock" 2>/dev/null; return 1; }
    fi
    rmdir "$lock" 2>/dev/null
  fi
  printf '%s' "$bin"
}

case "${1:-lint}" in
  lint)
    if [ -x node_modules/.bin/redocly ]; then bin=./node_modules/.bin/redocly
    elif [ -x "$script_root/node_modules/.bin/redocly" ]; then bin="$script_root/node_modules/.bin/redocly"
    elif command -v npm >/dev/null 2>&1; then bin="$(shared_redocly)" || exit 1
    else echo "skip: node/npm not available for redocly lint"; exit 0; fi
    "$bin" lint --config contracts/redocly.yaml contracts/openapi.yaml ;;
  *) echo "usage: contract.sh lint" >&2; exit 2 ;;
esac
