---
name: retro
description: Runs the end-of-phase retrospective in the retro-facilitator subagent (reads SDLC_METRICS, ESCALATIONS, review and verify reports, flaky tests, escaped defects; writes docs/retros/phase-N.md with keep/change/try; applies agreed edits to CLAUDE.md, rules, agent prompts and hooks with hooks-unlock/lock). Use when the owner says /retro or after a GREEN /phase-gate.
argument-hint: "[phase number]"
disable-model-invocation: true
user-invocable: true
context: fork
agent: retro-facilitator
background: false
---

# /retro — Phase $ARGUMENTS retrospective (you are `retro-facilitator`)

Phase: `$ARGUMENTS` if given, else `bash scripts/sdlc/state.sh get phase` minus 1 (the phase just gated).
Start with `source scripts/env.sh`. In a fresh worktree `make metrics` cannot see the gitignored
`.claude/state/` logs, coverage or eval reports of the main checkout and rewrites them as `0`/`no report`:
quote the committed `docs/SDLC_METRICS.md` and read `.claude/state/` in the main checkout instead, and do not
commit a worktree regeneration. Your write scope is docs/, .claude/, CLAUDE.md files (protect-files enforces it).

## 1. Inputs (read all, quote numbers)
- `docs/SDLC_METRICS.md` (run `make metrics` first so it is fresh), `docs/ESCALATIONS.md`, `docs/LESSONS.md`,
  `docs/phases/phase-N-report.md`, `docs/PROGRESS.md` lines for `PN`.
- Every `docs/backlog/*/STORY-*.verify.md` and `.review.md` of the phase: count findings by severity and by
  reviewer; count verify FAIL loops per story; list stories with `loop_count` 3 or BLOCKED.
- `.claude/state/test-integrity.log`, `.claude/state/hooks.log` (BLOCK lines = guard hits: which, how often),
  flaky-test quarantine list (`grep -rE 'quarantine|@Flaky|retries' --include=*.kts --include=*.ts --include=*.java -l .`
  and the nightly flaky report if present), escaped defects (LESSONS entries dated in the phase).
- Agents' project memory files (if `memory: project` created any) to prune stale items.

## 2. Analyse
For each pattern with at least 2 occurrences: what happened -> which stage should have caught it -> which
artifact (CLAUDE.md invariant, rule, agent prompt, hook, make target, template, threshold) would prevent it.
Stories costing more than 3x the median AI spend: say why (S17). Guard hits that blocked legitimate work: propose
the narrower rule. Review findings that recur across stories: propose a rule or a semgrep check.

## 3. Write `docs/retros/phase-N.md` from `docs/templates/retro.md`
Metrics snapshot table filled with real numbers; Keep / Change / Try each as concrete bullets (an agent name,
a file, a measurable outcome); "Applied changes" table filled in step 4.

## 4. Apply the agreed changes (each with a reason)
- Edit `CLAUDE.md`, module `CLAUDE.md`, `.claude/rules/*.md`, `.claude/agents/*.md`, skill files directly.
- Hooks and `.claude/settings.json` are protected: run `make hooks-unlock` (sets `hooks_unlocked`), edit, run
  the hook tests `make hooks-test`, then ALWAYS `make hooks-lock` and verify `bash scripts/sdlc/state.sh get hooks_unlocked`
  prints `false`. Never leave the session unlocked; never change a hook's blocking behavior without a test
  in `.claude/hooks/tests/`.
- Every change gets a row `| file | change | reason |` in the retro and one line in `docs/DECISIONS.md`.
- Prune stale agent memories; keep the ones that still match a recurring pitfall.
- Threshold changes (S9/S10) need an ADR (`/adr`), not a retro line alone.

## 5. Record
- `docs/LESSONS.md`: one entry per new lesson (what -> root cause class -> prevention).
- PROGRESS: `<ts> · PN · SDLC · done · INFO · retro phase N: <k> keep, <c> change, <t> try; <a> changes applied`.
- Commit `docs(retro): phase N [PHASE-N]`. Run `make check-fast MODULE=sdlc` (hook tests, lint) and quote it.

## Output contract (last thing you print, STATUS last line)
KEEP:
- <item>
CHANGE:
- <item -> artifact changed>
TRY:
- <experiment with success measure>
APPLIED_CHANGES:
- <file>: <change> — <reason>
STATUS: RETRO_DONE | BLOCKED
