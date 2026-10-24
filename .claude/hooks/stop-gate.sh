#!/usr/bin/env bash
# Stop: the main agent may not stop while `make check-fast` fails for modules changed since the
# last green state. Blocks (exit 2) up to 3 consecutive times; then lets the stop through but
# writes RED entries to docs/PROGRESS.md and docs/ESCALATIONS.md so nobody can claim green.
# Overrides for tests: CLAUDE_PROJECT_DIR, MASROOF_STATE_FILE, MASROOF_STATE_DIR, MAKE.
HOOK_NAME=stop-gate
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
MAKE_BIN="${MAKE:-make}"
MAX_BLOCKS="${MASROOF_STOP_MAX_BLOCKS:-3}"
cd "$PROJECT_DIR" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0
is_truthy "${MASROOF_SKIP_STOP_GATE:-}" && exit 0

# ---- fingerprint of the working tree (HEAD + staged/unstaged diff + untracked contents) ----
fingerprint() {
  {
    git rev-parse HEAD 2>/dev/null || echo none
    git diff HEAD 2>/dev/null
    git ls-files --others --exclude-standard -z 2>/dev/null | tr '\0' '\n' | while IFS= read -r f; do
      [ -f "$f" ] && { printf '%s\n' "$f"; cat -- "$f" 2>/dev/null; }
    done
  } | sha1sum | cut -c1-40
}
fp="$(fingerprint)"
last_green="$(state_get last_green)"
last_green_fp="${last_green%%@*}"
last_green_sha="${last_green##*@}"
[ "$fp" = "$last_green_fp" ] && exit 0

# ---- which modules changed since the last green? ----
# -z and core.quotePath=false: git would otherwise print non-ASCII names quoted ("backend/src/\331...")
# and they would map to no module (STORY-001 loop 3, F-11)
gitq() { git -c core.quotePath=false "$@" 2>/dev/null | tr '\0' '\n'; }
changed="$(gitq diff -z --name-only HEAD; gitq ls-files -z --others --exclude-standard)"
if [ -n "$last_green_sha" ] && [ "$last_green_sha" != "$last_green_fp" ] && git cat-file -e "$last_green_sha" 2>/dev/null; then
  changed+=$'\n'"$(gitq diff -z --name-only "$last_green_sha" HEAD)"
fi
modules=""
add_mod() { case " $modules " in *" $1 "*) ;; *) modules="${modules:+$modules }$1" ;; esac; }
while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in
    backend/*) add_mod backend ;;
    web/*) add_mod web ;;
    android/*) add_mod android ;;
    .claude/*|scripts/*|Makefile|lefthook.yml|.github/*|contracts/*|eval/*|infra/*|.semgrep/*|.gitleaks.toml|.shellcheckrc|.editorconfig) add_mod sdlc ;;
    */*) : ;;   # docs/, fixtures/ and other directories: no gate
    *.md|*.MD|*.txt|LICENSE*) : ;;   # root markdown / text: no gate
    *) add_mod sdlc ;;   # other root files: docker-compose.yml, Dockerfile*, package.json, *.yml, .gitignore ...
  esac
done <<< "$changed"
# No last_green yet (fresh state or a new worktree): a red change may already be committed, so the
# working-tree diff would be empty. Gate every module present in the repo once; a green run records
# last_green and later Stops only gate what changed.
if [ -z "$last_green" ]; then
  for m in backend web android; do
    [ -n "$(git ls-files -- "$m" 2>/dev/null | head -1)" ] && add_mod "$m"
  done
  [ -n "$(git ls-files -- Makefile scripts .claude/hooks .github 2>/dev/null | head -1)" ] && add_mod sdlc
fi
# shellcheck disable=SC2086
modules="$(printf '%s
' $modules | sort -u | tr '
' ' ' | sed 's/ *$//')"

if [ -z "$modules" ]; then
  state_set last_green "$fp@$(git rev-parse HEAD 2>/dev/null || echo none)"
  state_set stop_blocks 0
  exit 0
fi

# ---- run the gate ----
log="$STATE_DIR/last-check.log"
start=$(date +%s)
if command -v timeout >/dev/null 2>&1; then
  timeout "${MASROOF_STOP_TIMEOUT:-540}" "$MAKE_BIN" check-fast MODULE="$modules" > "$log" 2>&1; rc=$?
else
  "$MAKE_BIN" check-fast MODULE="$modules" > "$log" 2>&1; rc=$?
fi
dur=$(( $(date +%s) - start ))

if [ "$rc" -eq 0 ]; then
  state_set last_green "$fp@$(git rev-parse HEAD 2>/dev/null || echo none)"
  state_set stop_blocks 0
  log_hook "GREEN modules=[$modules] ${dur}s"
  exit 0
fi

blocks="$(state_get stop_blocks)"; blocks="${blocks:-0}"; blocks=$((blocks + 1))
state_set stop_blocks "$blocks"
summary="$(tail -n 40 "$log" 2>/dev/null)"

if (( blocks < MAX_BLOCKS )); then
  log_hook "RED block $blocks/$MAX_BLOCKS modules=[$modules] rc=$rc"
  block "make check-fast MODULE=\"$modules\" failed (exit $rc, ${dur}s) - block $blocks/$MAX_BLOCKS. You may not stop while the gate is red: fix the failure, re-run 'make check-fast MODULE=\"$modules\"', then finish. Last 40 lines of .claude/state/last-check.log:
$summary"
fi

# ---- third consecutive failure: allow the stop, record RED so it cannot be claimed green ----
ts="$(date -u '+%Y-%m-%d %H:%M')"
phase="$(state_get phase)"; story="$(state_get story)"; stage="$(state_get stage)"
first_fail="$(grep -nE 'FAIL|error|Error|ERROR|failed' "$log" 2>/dev/null | head -3 | tr '\n' ' ' | cut -c1-300)"
printf '%s %s P%s %s %s %s %s %s RED %s stop-gate: make check-fast MODULE="%s" failed %s times in a row (exit %s). Agent stopped with a red gate. %s\n' \
  "$ts" "$DOT" "${phase:-?}" "$DOT" "${story:-SDLC}" "$DOT" "${stage:-?}" "$DOT" "$DOT" "$modules" "$blocks" "$rc" "$first_fail" >> "$PROJECT_DIR/docs/PROGRESS.md" 2>/dev/null || true
{
  printf -- '- [ ] **GATE-%s stop-gate RED** - `make check-fast MODULE="%s"` failed %s consecutive times (story: %s, stage: %s). ' "$(date -u +%Y%m%d%H%M%S)" "$modules" "$blocks" "${story:-SDLC}" "${stage:-?}"
  printf 'Tried: the agent attempted fixes across %s stop attempts. Evidence: `.claude/state/last-check.log`. Options: (a) fix in the next session, (b) revert the change, (c) descope. Decision needed by the orchestrator/owner.\n' "$blocks"
} >> "$PROJECT_DIR/docs/ESCALATIONS.md" 2>/dev/null || true
state_set stop_blocks 0
log_hook "RED escalated after $blocks blocks modules=[$modules]"
system_message "stop-gate: gate still RED after $blocks attempts (make check-fast MODULE=\"$modules\", exit $rc). Stop allowed; RED entries appended to docs/PROGRESS.md and docs/ESCALATIONS.md."
