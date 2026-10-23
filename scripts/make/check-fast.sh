#!/usr/bin/env bash
# make check-fast MODULE="sdlc backend web android"
# Per-module fast gate (< 3 min): compile, format check, lint, unit tests.
# Each product module exposes ONE entrypoint (`checkFast` gradle task / `npm run check-fast`)
# so this script never changes when a module's toolchain does.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$PROJECT_DIR" || exit 1
export LC_ALL=C.UTF-8
mods="$*"; [ -z "$mods" ] && mods="sdlc"
fail=0
start_all=$(date +%s)

step() { printf '\n--- %s\n' "$*"; }
run() { # run <desc> <cmd...>
  local desc="$1"; shift
  step "$desc"
  if "$@"; then printf 'OK   %s\n' "$desc"; else printf 'FAIL %s (exit %s)\n' "$desc" "$?"; fail=1; fi
}

check_sdlc() {
  local sh_files
  sh_files="$( { git ls-files -- '*.sh' 2>/dev/null; find .claude scripts infra -name '*.sh' 2>/dev/null; } | sort -u | grep -v node_modules || true)"
  # shellcheck disable=SC2086
  [ -n "$sh_files" ] && run "shellcheck ($(printf '%s\n' "$sh_files" | wc -l | tr -d ' ') scripts)" shellcheck -x -S warning $sh_files
  if [ -f .claude/settings.json ]; then run "settings.json is valid JSON" jq empty .claude/settings.json; fi
  for f in .claude/hooks/output-contracts.json eval/baseline.json eval/schema/case.schema.json .github/labeler.yml; do
    case "$f" in *.json) [ -f "$f" ] && run "valid JSON: $f" jq empty "$f" ;; esac
  done
  local yml_files
  yml_files="$(find .github eval .claude lefthook.yml .semgrep -name '*.yml' -o -name '*.yaml' 2>/dev/null | sort -u || true)"
  if [ -n "$yml_files" ]; then
    # shellcheck disable=SC2086
    run "YAML parses ($(printf '%s\n' "$yml_files" | wc -l | tr -d ' ') files)" "$PYTHON" -c '
import sys, yaml
bad = 0
for f in sys.argv[1:]:
    try:
        yaml.safe_load(open(f, encoding="utf-8"))
    except Exception as e:
        print(f"{f}: {e}"); bad = 1
sys.exit(bad)' $yml_files
  fi
  [ -f scripts/quality/sdlc-lint.py ] && run "sdlc-lint (agents, skills, rules, settings, stories, workflows)" "$PYTHON" scripts/quality/sdlc-lint.py
  local py_files
  py_files="$(find scripts eval -name '*.py' -not -path '*/.venv/*' 2>/dev/null | sort || true)"
  # shellcheck disable=SC2086
  [ -n "$py_files" ] && run "python compiles" "$PYTHON" -m py_compile $py_files
  [ -f eval/runners/run.py ] && run "eval datasets validate against schema" "$PYTHON" eval/runners/run.py --validate-only
  # The three slow, independent suites run in parallel to stay inside the 3-minute budget.
  local logdir pids=() names=() n=0
  logdir="$(mktemp -d)"
  start_bg() { # start_bg <name> <cmd...>
    names+=("$1"); shift; ( "$@" ) > "$logdir/$n.log" 2>&1 & pids+=($!); n=$((n + 1)); }
  [ -f .claude/hooks/tests/run.sh ] && start_bg "hook tests" bash .claude/hooks/tests/run.sh
  [ -f scripts/quality/tests/run.sh ] && start_bg "quality script tests" bash scripts/quality/tests/run.sh
  [ -f contracts/openapi.yaml ] && [ -f scripts/make/contract.sh ] && start_bg "contract lint" bash scripts/make/contract.sh lint
  local i
  for i in "${!pids[@]}"; do
    step "${names[$i]} (parallel)"
    if wait "${pids[$i]}"; then tail -n 3 "$logdir/$i.log"; printf 'OK   %s
' "${names[$i]}"
    else cat "$logdir/$i.log"; printf 'FAIL %s
' "${names[$i]}"; fail=1; fi
  done
  rm -rf "$logdir"
  return 0
}

check_backend() {
  [ -x backend/gradlew ] || { echo "skip: backend module not present"; return 0; }
  run "backend: ./gradlew checkFast" bash -c 'cd backend && ./gradlew --console=plain -q checkFast'
}
check_web() {
  [ -f web/package.json ] || { echo "skip: web module not present"; return 0; }
  [ -d web/node_modules ] || run "web: npm ci" bash -c 'cd web && npm ci --no-audit --no-fund'
  run "web: npm run check-fast" bash -c 'cd web && npm run -s check-fast'
}
check_android() {
  [ -x android/gradlew ] || { echo "skip: android module not present"; return 0; }
  run "android: ./gradlew checkFast" bash -c 'cd android && ./gradlew --console=plain -q checkFast'
}

for m in $mods; do
  printf '\n===== check-fast: %s =====\n' "$m"
  case "$m" in
    sdlc) check_sdlc ;;
    backend) check_backend ;;
    web) check_web ;;
    android) check_android ;;
    *) echo "unknown module '$m' (expected sdlc|backend|web|android)"; fail=1 ;;
  esac
done

dur=$(( $(date +%s) - start_all ))
if [ "$fail" -eq 0 ]; then printf '\ncheck-fast GREEN [%s] in %ss\n' "$mods" "$dur"; else printf '\ncheck-fast RED [%s] in %ss\n' "$mods" "$dur"; exit 1; fi
