---
name: verify
description: Runs qa-verifier in a fresh worktree context against one story's acceptance criteria with a "Claims to verify" list (never the implementer's summary), then writes the .verify.md report and decides PASS or a fix loop (max 3). Use when the owner says /verify STORY-NNN or from /story stage 6.
argument-hint: "STORY-NNN"
disable-model-invocation: true
user-invocable: true
---

# /verify $0 — stage 6 (independent verification)

## 0. Preconditions
- Branch `story/$0-<slug>` clean, `make check-fast` green (`/implement` done).
- `BASE=$(git merge-base main story/$0-<slug>)`, `HEAD=$(git rev-parse HEAD)`, `N=$(( $(bash scripts/sdlc/state.sh get loop_count) + 1 ))`.
- `source scripts/env.sh`; `bash scripts/sdlc/state.sh set story $0; ... set stage verify`.
- Claims list: REQ ids with their criteria in neutral words + known-limitation claims (from `/implement` step 3).
  If you do not have it, rebuild it from the story file only. Never read the implementer's report to the verifier.
- Snapshot worktrees ([../story/worktree-merge.md](../story/worktree-merge.md)); the verifier is read-only but
  may leave a worktree behind to delete.

## 1. Verifier — agent `qa-verifier` (worktree, fresh)
Prompt from [../story/prompts/qa-verifier.md](../story/prompts/qa-verifier.md): story path, branch, `BASE`,
`HEAD`, loop `N`, make targets, claims list. Expect `STATUS: PASS | FAIL | BLOCKED` with `STORY`, `CRITERIA`,
`FINDINGS`, `EXPLORATORY`, `TEST_INTEGRITY`.

## 2. Write the report (verifiers have no Write tool)
Create or overwrite `<story dir>/$0-<slug>.verify.md` from `docs/templates/verify-report.md`:
header (verifier, date, `BASE`, `HEAD`, loop `N of 3`), Environment = the `make check-fast` line from the
report, Criteria table from `CRITERIA`, Findings table from `FINDINGS`, Exploratory and Test integrity lines
verbatim, last line `STATUS: <value>`. Do not edit the verdict or soften a finding.
Commit `docs($0): verify report loop N [$0]`.

## 3. Decide
- `PASS` and no BLOCKER/MAJOR in FINDINGS and `TEST_INTEGRITY: OK` -> Status log row
  `| <date> | verify | qa-verifier | PASS loop N |`; PROGRESS `... · $0 · verify · GREEN · PASS loop N; <k>/<k> criteria; exploratory <n>/<m>`.
  Next: `/review $0`.
- `PASS` but with a BLOCKER/MAJOR finding or `TEST_INTEGRITY: VIOLATION` -> treat as FAIL (the contract allows
  PASS only with none open).
- `FAIL` -> `bash scripts/sdlc/state.sh set loop_count N`. If `N > 3`: escalate
  ([../story/escalation.md](../story/escalation.md)), `status: BLOCKED`, stop. Else PROGRESS
  `... · verify · RED · FAIL loop N; <first BLOCKER/MAJOR>` and run `/implement $0 FINDINGS=<verify.md>`.
- `BLOCKED` (environment: `make up` fails, port busy, missing fixture) -> fix as orchestrator glue, relaunch;
  counts as a loop only if the cause is in the change.

## Exit gate
`<story>.verify.md` committed and ends `STATUS: PASS` with zero BLOCKER/MAJOR; otherwise the fix loop or the
escalation was executed and recorded. Never paraphrase a FAIL as "mostly passing".
