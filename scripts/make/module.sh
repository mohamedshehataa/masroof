#!/usr/bin/env bash
# make <mutation|...>: run a named gradle task in a module, skipping cleanly when the module is absent.
#   module.sh backend mutation      -> cd backend && ./gradlew pitest
#   module.sh backend migrationTest -> cd backend && ./gradlew migrationTest
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
. "$(dirname "${BASH_SOURCE[0]}")/gradlew-guard.sh"
mod="${1:?module}"; task="${2:?task}"
case "$mod" in
  backend|android)
    gradlew_state "$mod"; case $? in 2) echo "skip: $mod module not present"; exit 0 ;; 1) exit 1 ;; esac
    case "$task" in mutation) gtask=pitest ;; *) gtask="$task" ;; esac
    if [ "$task" = mutation ] && [ "$mod" = backend ]; then
      # PIT with zero target classes writes no mutations.xml. Leave an explicit marker (only when PIT itself says so)
      # so coverage-gate.py reports SKIP with a reason instead of a missing report. Stale evidence is cleared first.
      pitdir="$mod/build/reports/pitest"; log="$(mktemp)"
      rm -f "$pitdir/no-mutations.txt" "$pitdir/mutations.xml"
      set +e
      (cd "$mod" && ./gradlew --console=plain -q pitest --rerun) 2>&1 | tee "$log"
      rc="${PIPESTATUS[0]}"
      set -e
      if [ "$rc" -eq 0 ] && [ ! -f "$pitdir/mutations.xml" ] && grep -q "No mutations found" "$log"; then
        ntargets="$("$PYTHON" scripts/quality/pit-targets.py 2>/dev/null)" || ntargets=error
        if [ "$ntargets" = 0 ]; then
          mkdir -p "$pitdir"
          echo "PIT found no mutations: no classes in the mutation target packages yet" > "$pitdir/no-mutations.txt"
        else
          echo "module.sh: PIT found no mutations although target classes exist ($ntargets): check the classpath/filters" >&2
          rc=1
        fi
      fi
      rm -f "$log"
      exit "$rc"
    fi
    (cd "$mod" && ./gradlew --console=plain -q "$gtask") ;;
  web)
    [ -f web/package.json ] || { echo "skip: web module not present"; exit 0; }
    (cd web && npm run -s "$task") ;;
  *) echo "unknown module $mod" >&2; exit 2 ;;
esac
