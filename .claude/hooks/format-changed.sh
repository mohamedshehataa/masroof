#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): format just the changed file, run its fast linter,
# and feed problems back as additionalContext. Never blocks, never fails.
HOOK_NAME=format-changed
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
read_input
fp="$(json_get '.tool_input.file_path')"
[ -z "$fp" ] && exit 0
abs="$(to_posix_path "$fp")"
[ -f "$abs" ] || exit 0
rel="$(rel_to_project "$fp")"
ext="${rel##*.}"
out=""
run() { out+="$("$@" 2>&1 | head -40)"; }

case "$ext" in
  sh)
    command -v shellcheck >/dev/null 2>&1 && run shellcheck -x "$abs" ;;
  py)
    if command -v ruff >/dev/null 2>&1; then ruff format -q "$abs" >/dev/null 2>&1; run ruff check -q "$abs"; fi
    "$PYTHON" -m py_compile "$abs" 2>/dev/null || run "$PYTHON" -m py_compile "$abs" ;;
  java)
    if [ -x "$PROJECT_DIR/backend/gradlew" ] && [[ "$rel" == backend/* ]]; then
      (cd "$PROJECT_DIR/backend" && ./gradlew -q spotlessApply -PspotlessIdeHook="$abs" --offline >/dev/null 2>&1) || true
    fi ;;
  kt|kts)
    if command -v ktlint >/dev/null 2>&1; then run ktlint -F "$abs"; fi ;;
  ts|tsx|js|jsx|css|json|md|yml|yaml|html)
    if [[ "$rel" == web/* ]] && [ -x "$PROJECT_DIR/web/node_modules/.bin/prettier" ]; then
      (cd "$PROJECT_DIR/web" && ./node_modules/.bin/prettier --log-level warn --write "$abs" >/dev/null 2>&1) || true
      if [[ "$ext" =~ ^(ts|tsx|js|jsx)$ ]] && [ -x "$PROJECT_DIR/web/node_modules/.bin/eslint" ]; then
        run bash -c "cd '$PROJECT_DIR/web' && ./node_modules/.bin/eslint --no-warn-ignored --fix '$abs'"
      fi
    elif [ -x "$PROJECT_DIR/node_modules/.bin/prettier" ]; then
      (cd "$PROJECT_DIR" && ./node_modules/.bin/prettier --log-level warn --write "$abs" >/dev/null 2>&1) || true
    fi
    if [ "$ext" = json ] && have_jq; then jq empty "$abs" 2>/dev/null || run jq empty "$abs"; fi
    if [[ "$ext" =~ ^(yml|yaml)$ ]]; then
      "$PYTHON" -c 'import sys,yaml; yaml.safe_load(open(sys.argv[1],encoding="utf-8"))' "$abs" 2>/dev/null \
        || run "$PYTHON" -c 'import sys,yaml; yaml.safe_load(open(sys.argv[1],encoding="utf-8"))' "$abs"
    fi ;;
esac

if [ -n "$out" ]; then
  feedback "Lint feedback for $rel:
$out"
fi
exit 0
