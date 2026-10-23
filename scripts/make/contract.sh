#!/usr/bin/env bash
# make contract-lint -> contract.sh lint   (redocly via npx; pinned major)
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/stubs.sh"
[ -f contracts/openapi.yaml ] || { echo "skip: contracts/openapi.yaml not present"; exit 0; }
case "${1:-lint}" in
  lint)
    if [ -x node_modules/.bin/redocly ]; then ./node_modules/.bin/redocly lint --config contracts/redocly.yaml contracts/openapi.yaml
    elif command -v npx >/dev/null 2>&1; then npx --yes @redocly/cli@2 lint --config contracts/redocly.yaml contracts/openapi.yaml
    else echo "skip: node/npx not available for redocly lint"; fi ;;
  *) echo "usage: contract.sh lint" >&2; exit 2 ;;
esac
