---
name: review
description: Runs code-reviewer plus every triggered specialist (security, ux-a11y + i18n-rtl, performance, ai-eval) in parallel on one story's diff, merges their findings into the .review.md report and decides APPROVE or a fix loop. Use when the owner says /review STORY-NNN or from /story stage 7; never with the author's summary.
argument-hint: "STORY-NNN"
disable-model-invocation: true
user-invocable: true
---

# /review $0 — stage 7 (parallel review)

## 0. Preconditions
- `<story>.verify.md` ends `STATUS: PASS` (else `/verify $0`). Branch clean.
- `BASE=$(git merge-base main story/$0-<slug>)`, `HEAD=$(git rev-parse HEAD)`,
  `CHANGED=$(git diff --name-only $BASE...$HEAD)`; `N=$(bash scripts/sdlc/state.sh get loop_count)`.
- `source scripts/env.sh`; `bash scripts/sdlc/state.sh set story $0; ... set stage review`.
- Claims list: same as /verify. Snapshot worktrees (performance-engineer runs in one).

## 1. Choose reviewers
`code-reviewer` always. For each row in the trigger table of [../story/triggers.md](../story/triggers.md), check
`CHANGED` (plus the story's impact sections) and add the reviewer. `ux-a11y-reviewer` and `i18n-rtl-reviewer`
always go together. Write the list down before launching (it goes into the report header).

## 2. Launch in ONE message (parallel)
- `code-reviewer`: [../story/prompts/code-reviewer.md](../story/prompts/code-reviewer.md)
- specialists: [../story/prompts/specialist-reviewers.md](../story/prompts/specialist-reviewers.md);
  `security-reviewer` diff mode: [../story/prompts/security-reviewer.md](../story/prompts/security-reviewer.md)
Each gets: story path, `BASE`, `HEAD`, how to run, `<story>.verify.md`, claims list, its trigger. Nothing else.

## 3. Merge the findings into `<story dir>/$0-<slug>.review.md`
From `docs/templates/review-report.md`: Reviewers line = the list from step 1; Diff line with the refs;
Findings table = every FINDINGS bullet from every report, columns `# | Severity | Reviewer | Location | Why |
Suggested fix | Resolution` (Resolution empty now), sorted BLOCKER > MAJOR > MINOR > NIT; the specialists'
extra sections (`STATES_CHECKED`, `MEASUREMENTS`, `METRICS`/`REGRESSIONS`, `THREAT_MODEL_UPDATED`) pasted under
"Invariants checked"; tick the invariant boxes only when a reviewer explicitly confirmed them.
Last line: `STATUS: APPROVE` only if every reviewer said APPROVE and no BLOCKER/MAJOR exists; else
`STATUS: CHANGES_REQUESTED`. Commit `docs($0): review report loop <N+1> [$0]`.

## 4. Decide
- APPROVE: for MINOR/NIT left unfixed, add a Status log row "follow-ups: <ids>" (or a new story if > 5).
  Status log `| <date> | review | <reviewers> | APPROVE |`; PROGRESS `... · $0 · review · GREEN · APPROVE by <reviewers>; findings B/M/m/n = a/b/c/d`.
  Next: stage 8 ([../story/integrate.md](../story/integrate.md)) or `/story $0` to finish.
- CHANGES_REQUESTED: `loop_count = N+1`; if `> 3` -> escalate, `status: BLOCKED`. Else
  `/implement $0 FINDINGS=<review.md>` -> `/verify $0` -> `/review $0` (re-run ALL reviewers; a fix can break
  something another reviewer owns). If `THREAT_MODEL_UPDATED: required`, edit
  `docs/security/THREAT_MODEL.md` with the reviewer's text as orchestrator glue before relaunching.
- `ai-eval-engineer` `REJECT` is CHANGES_REQUESTED; `BLOCKED` for a missing key -> rerun with the reference adapter noted.

## Exit gate
`<story>.review.md` committed, ends `STATUS: APPROVE`, zero BLOCKER/MAJOR, every triggered reviewer listed with
its verdict; or the fix loop / escalation was executed and recorded.
