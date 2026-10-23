#!/usr/bin/env bash
# make <mutation|...>: run a named gradle task in a module, skipping cleanly when the module is absent.
#   module.sh backend mutation      -> cd backend && ./gradlew pitest
#   module.sh backend migrationTest -> cd backend && ./gradlew migrationTest
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
mod="${1:?module}"; task="${2:?task}"
case "$mod" in
  backend|android)
    [ -x "$mod/gradlew" ] || { echo "skip: $mod module not present"; exit 0; }
    case "$task" in mutation) gtask=pitest ;; *) gtask="$task" ;; esac
    (cd "$mod" && ./gradlew --console=plain -q "$gtask") ;;
  web)
    [ -f web/package.json ] || { echo "skip: web module not present"; exit 0; }
    (cd web && npm run -s "$task") ;;
  *) echo "unknown module $mod" >&2; exit 2 ;;
esac
