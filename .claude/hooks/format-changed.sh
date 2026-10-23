#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): format just the changed file, run its fast linter,
# and feed problems back as additionalContext. Never blocks, never fails.
# Findings are one per line as `<file>:<line>: <message>`. When a linter is not installed the hook
# prints exactly one stderr line `format-changed: <tool> not installed, skipped` and exits 0.
# Tool overrides for tests: MASROOF_SHELLCHECK (default shellcheck).
HOOK_NAME=format-changed
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
fp="$(json_get '.tool_input.file_path')"
[ -z "$fp" ] && exit 0
abs="$(to_posix_path "$fp")"
[ -f "$abs" ] || exit 0
rel="$(rel_to_project "$fp")"
ext="${rel##*.}"; ext="${ext,,}"
out=""
add() { [ -n "$1" ] && out+="${out:+$'\n'}$1"; return 0; }
skipped() { printf 'format-changed: %s not installed, skipped\n' "$1" >&2; }
SHELLCHECK="${MASROOF_SHELLCHECK:-shellcheck}"
have_python() { command -v "${PYTHON%% *}" >/dev/null 2>&1; }

# py_lint <kind> : syntax check of $abs with Python, printed as "$rel:<line>: <message>"
# (kind = py | json | yaml). The file arrives on stdin, so no path is handed to native python.
py_lint() {
  # $PYTHON may be "py -3" (two words), so it is intentionally unquoted
  # shellcheck disable=SC2086
  F_REL="$rel" F_KIND="$1" $PYTHON -c '
import json, os, sys
rel, kind = os.environ["F_REL"], os.environ["F_KIND"]
src = sys.stdin.buffer.read().decode("utf-8", "replace")
try:
    if kind == "py":
        compile(src, rel, "exec")
    elif kind == "json":
        json.loads(src)
    else:
        import yaml
        list(yaml.safe_load_all(src))
except ImportError:
    print("NO_PYYAML")
except SyntaxError as e:
    print("%s:%s: SyntaxError: %s" % (rel, e.lineno or 1, e.msg))
except json.JSONDecodeError as e:
    print("%s:%s: invalid JSON: %s" % (rel, e.lineno, e.msg))
except Exception as e:
    mark = getattr(e, "problem_mark", None)
    line = (mark.line + 1) if mark is not None else 1
    msg = " ".join(str(getattr(e, "problem", None) or e).split())
    print("%s:%s: invalid YAML: %s" % (rel, line, msg))
' < "$abs" 2>/dev/null
}

case "$ext" in
  sh|bash)
    if command -v "$SHELLCHECK" >/dev/null 2>&1; then
      # json1 -> "file:line: level: message [SCxxxx]"; the file path goes through env (MSYS-safe)
      if have_jq; then
        add "$("$SHELLCHECK" -x -f json1 "$abs" 2>/dev/null | F_REL="$rel" jq -r '.comments[]? | "\(env.F_REL):\(.line): \(.level): \(.message) [SC\(.code)]"' 2>/dev/null | head -40)"
      else
        add "$("$SHELLCHECK" -x -f gcc "$abs" 2>/dev/null | sed -E "s#^.*:([0-9]+):[0-9]+: #$rel:\1: #" | head -40)"
      fi
    else
      skipped shellcheck
    fi ;;
  py)
    if command -v ruff >/dev/null 2>&1; then
      ruff format -q "$abs" >/dev/null 2>&1
      add "$(ruff check -q --output-format concise "$abs" 2>/dev/null | sed -E "s#^.*:([0-9]+):[0-9]+: #$rel:\1: #" | head -40)"
    fi
    if have_python; then add "$(py_lint py)"; else skipped python; fi ;;
  java)
    if [ -x "$PROJECT_DIR/backend/gradlew" ] && [[ "$rel" == backend/* ]]; then
      (cd "$PROJECT_DIR/backend" && ./gradlew -q spotlessApply -PspotlessIdeHook="$abs" --offline >/dev/null 2>&1) || true
    fi ;;
  kt|kts)
    if command -v ktlint >/dev/null 2>&1; then
      add "$(ktlint -F --reporter=plain "$abs" 2>/dev/null | sed -E "s#^.*:([0-9]+):[0-9]+: #$rel:\1: #" | head -40)"
    else
      skipped ktlint
    fi ;;
  ts|tsx|js|jsx|css|json|md|yml|yaml|html)
    if [[ "$rel" == web/* ]] && [ -x "$PROJECT_DIR/web/node_modules/.bin/prettier" ]; then
      (cd "$PROJECT_DIR/web" && ./node_modules/.bin/prettier --log-level warn --write "$abs" >/dev/null 2>&1) || true
      if [[ "$ext" =~ ^(ts|tsx|js|jsx)$ ]] && [ -x "$PROJECT_DIR/web/node_modules/.bin/eslint" ]; then
        # a subshell with array args: the file name is never re-parsed by a shell
        add "$( (cd "$PROJECT_DIR/web" && ./node_modules/.bin/eslint --no-warn-ignored --fix --format unix -- "$abs") 2>&1 | grep -E ':[0-9]+:' | sed -E "s#^.*:([0-9]+):[0-9]+: #$rel:\1: #" | head -40)"
      fi
    elif [ -x "$PROJECT_DIR/node_modules/.bin/prettier" ]; then
      (cd "$PROJECT_DIR" && ./node_modules/.bin/prettier --log-level warn --write "$abs" >/dev/null 2>&1) || true
    fi
    if [ "$ext" = json ]; then
      if have_python; then add "$(py_lint json)"
      elif have_jq; then
        jq empty "$abs" >/dev/null 2>&1 || add "$rel:$(jq empty "$abs" 2>&1 | grep -oE 'line [0-9]+' | head -1 | tr -dc '0-9' || echo 1): invalid JSON"
      else skipped python; fi
    fi
    if [[ "$ext" =~ ^(yml|yaml)$ ]]; then
      if command -v yamllint >/dev/null 2>&1; then
        add "$(yamllint -f parsable -d relaxed "$abs" 2>/dev/null | grep -F '[error]' | sed -E "s#^.*:([0-9]+):[0-9]+: #$rel:\1: #" | head -40)"
      elif have_python; then
        res="$(py_lint yaml)"
        if [ "$res" = NO_PYYAML ]; then skipped yamllint; else add "$res"; fi
      else
        skipped yamllint
      fi
    fi ;;
esac

if [ -n "$out" ]; then
  feedback "Lint feedback for $rel:
$out"
fi
exit 0
