#!/usr/bin/env bash
# SessionStart(startup|resume|compact|clear): print the SDLC state so context survives compaction.
# stdout is added to Claude's context for this event; it is capped at 9500 bytes (10k context cap).
HOOK_NAME=inject-context
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
source_kind="$(json_get '.source')"
cd "$PROJECT_DIR" 2>/dev/null || exit 0

context() {
  local phase story stage loops unlocked release ready inprog blocked done_ open_count
  phase="$(state_get phase)"; story="$(state_get story)"; stage="$(state_get stage)"; loops="$(state_get loop_count)"
  unlocked="$(state_get hooks_unlocked)"; release="$(state_get release_active)"

  echo "## Masroof SDLC context (SessionStart: ${source_kind:-unknown})"
  echo "- Phase: ${phase:-?} | Active story: ${story:-none} | Stage: ${stage:-?} | Verify/review loop: ${loops:-0}/3"
  [ "$unlocked" = true ] && echo "- WARNING: hooks are UNLOCKED (make hooks-lock when done)"
  [ "$release" = true ] && echo "- WARNING: /release is ACTIVE (production commands allowed)"
  echo "- Branch: $(git symbolic-ref --short HEAD 2>/dev/null || echo detached) | Dirty files: $(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"

  if [ -d docs/backlog ]; then
    ready=$(grep -rlE '^status:[[:space:]]*READY' docs/backlog --include='STORY-*.md' 2>/dev/null | wc -l | tr -d ' ')
    inprog=$(grep -rlE '^status:[[:space:]]*IN_PROGRESS' docs/backlog --include='STORY-*.md' 2>/dev/null | wc -l | tr -d ' ')
    blocked=$(grep -rlE '^status:[[:space:]]*BLOCKED' docs/backlog --include='STORY-*.md' 2>/dev/null | wc -l | tr -d ' ')
    done_=$(grep -rlE '^status:[[:space:]]*DONE' docs/backlog --include='STORY-*.md' 2>/dev/null | wc -l | tr -d ' ')
    echo "- Backlog: READY $ready | IN_PROGRESS $inprog | BLOCKED $blocked | DONE $done_"
  fi

  echo
  if [ -f docs/PROGRESS.md ]; then
    echo "### Last 20 lines of docs/PROGRESS.md"
    grep -vE '^[[:space:]]*$' docs/PROGRESS.md | tail -n 20 | cut -c1-300
  else
    echo "PROGRESS.md: none"
  fi

  if [ -f docs/ESCALATIONS.md ]; then
    # grep -c prints 0 itself when nothing matches (and exits 1), so no `|| echo 0`
    open_count="$(grep -cE '^- \[ \]' docs/ESCALATIONS.md 2>/dev/null)"
    echo; echo "### Open escalations (${open_count:-0})"
    grep -E '^- \[ \]' docs/ESCALATIONS.md | cut -c1-160 | head -12
  fi

  if [ -f CLAUDE.md ]; then
    echo; echo "### Invariants (from CLAUDE.md)"
    sed -n '/^## Invariants/,/^## /p' CLAUDE.md | grep -E '^[-*] ' | cut -c1-220 | head -12
  fi

  echo; echo "Workflow reminder: spec -> spec-review -> design -> failing tests -> implement -> qa-verifier (fresh) -> reviewers -> make check -> merge. Max 3 verify/review loops, then docs/ESCALATIONS.md. Evidence or it didn't happen."
}

context | head -c 9500
exit 0
