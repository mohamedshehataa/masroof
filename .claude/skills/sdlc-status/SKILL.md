---
name: sdlc-status
description: Prints the current SDLC state (phase, active story, stage, loop count), stories per status from the backlog frontmatter, the last gate and eval results, open escalations and AI spend. Use when the owner asks /sdlc-status, "where are we", or before picking the next story. Read-only.
argument-hint: ""
disable-model-invocation: true
user-invocable: true
allowed-tools: Bash(bash *), Read, Grep, Glob
---

# /sdlc-status — read-only snapshot

The data below was collected by [status.sh](status.sh) when this skill loaded. Do not re-run commands unless a
section says "missing"; do not modify any file.

!`bash "${CLAUDE_SKILL_DIR}/status.sh"`

## What to report (in this order, short)
1. **Where we are:** `P<phase> · <story or none> · <stage> · loop <n>/3`. Flag `hooks_unlocked`,
   `release_active` or `rebaseline_allowed` being true (each must be false between skills; say which skill
   owns locking it back: `/retro`, `/sdlc-selftest`, `/release`, `/eval`).
2. **Backlog:** the counts table; the next candidate per `/next` rules (READY, all `depends_on` DONE, lowest
   phase then lowest id) and anything IN_PROGRESS or BLOCKED.
3. **Gates:** GREEN/RED of the last `make check-fast` from the log tail (red -> quote the first failing line),
   and the latest eval report's gate summary (or "no report").
4. **Escalations:** count and the ids (`OWNER-NNN`, `STORY-NNN`, `GATE-...`); which ones only the owner can clear.
5. **Spend:** AI spend vs budget, ledger rows; warn at >= 80% of the budget.
6. **Suggested next command:** one of `/next`, `/story <id>` (resume IN_PROGRESS), `/sdlc-selftest` (Phase 0
   not proven), `/phase-gate <n>` (all phase stories DONE), `/retro` (gate report GREEN), or "resolve
   escalation <id> first".

If the state file says a story is IN_PROGRESS but no `story/STORY-...` branch exists (or vice versa), say so:
that inconsistency is the first thing to fix (`bash scripts/sdlc/state.sh set story <id|null>`).
