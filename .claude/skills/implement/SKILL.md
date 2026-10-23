---
name: implement
description: Runs the implementation stage for one story by routing to the right implementer subagent (backend-engineer, ai-parsing-engineer, web-engineer, android-engineer or devops-engineer from the story's owner_agent/modules), in a worktree, until make check-fast is green and the failing tests pass. Use when the owner says /implement STORY-NNN or from /story stage 5.
argument-hint: "STORY-NNN [TESTS_SHA=<sha>] [FINDINGS=<path to verify/review report>]"
disable-model-invocation: true
user-invocable: true
---

# /implement $0 — stage 5

## 0. Preconditions
- Branch `story/$0-<slug>` checked out and clean; failing tests merged (`/tdd`). `TESTS_SHA` from the argument
  or `git log --oneline --grep='^test(' story/$0-<slug> | head -1`.
- `source scripts/env.sh`; `bash scripts/sdlc/state.sh set story $0; ... set stage implement`.
- Read frontmatter `owner_agent` and `modules`. Route per "Implementer routing" in
  [../story/triggers.md](../story/triggers.md). Multi-module: one implementer per module, order
  contract -> backend -> clients; clients may run in parallel worktrees (cap 4).
- Snapshot worktrees/branches ([../story/worktree-merge.md](../story/worktree-merge.md)).

## 1. Implementer — agent `<routed name>` (worktree)
Prompt from [../story/prompts/implementer.md](../story/prompts/implementer.md): story path, branch, `TESTS_SHA`,
module gate `make check-fast MODULE=<m>`, design notes. If `FINDINGS=` was given (fix loop), paste the FINDINGS
section of that report verbatim and state the loop number (`bash scripts/sdlc/state.sh get loop_count` + 1).
`ai-parsing-engineer` must also report `EVAL_RESULT`. Expect `STATUS: READY_FOR_VERIFY`.

## 2. Gate the report and merge
1. `BLOCKED` -> test defect named? forward the exact assertion to `test-designer` (one relaunch; it may fix its
   own tests). Design gap? `/design $0` again. Owner input? escalate ([../story/escalation.md](../story/escalation.md)).
2. Merge the worktree branch (squash) with `feat|fix|refactor(<scope>): <what> [$0]`.
3. Run the gate yourself: `make check-fast MODULE=<m>` (every touched module). RED -> relaunch the implementer
   with the last 40 lines of output as FINDINGS; this does not increment `loop_count` (that counter is for
   verify/review loops), but after 3 red gates in a row escalate anyway.
4. Test integrity: `cat .claude/state/test-integrity.log 2>/dev/null | tail -20`. Any line for a
   test-designer test touched by the implementer must be explained in `KNOWN_LIMITATIONS`; unexplained ->
   relaunch with "revert the test change or explain it".
5. For parsing/prompt/model changes: `EVAL_RESULT` present and no metric down > 1 pp; else back to step 1.

## 3. Build "Claims to verify" (for /verify and /review)
From the story REQs (one line each, criterion in neutral words) plus each `KNOWN_LIMITATIONS` line rewritten
as a testable claim ("X is forced to PENDING_REVIEW above the ceiling"). Save it in your working notes; it is
the ONLY implementer-derived text that reaches the verifier or reviewers. Never forward `FILES_CHANGED`
prose, commit bodies or the agent's explanation.

## 4. Record
- Story Status log row `| <date> | implement | <agent> | READY_FOR_VERIFY (loop <n>) |`.
- PROGRESS line: `<ts> · P<phase> · $0 · implement · GREEN · <agent>; make check-fast MODULE=<m> green; HEAD <short sha>`.

## Exit gate
`READY_FOR_VERIFY` quoted, worktree merged, `make check-fast` green on the story branch for every touched
module, claims list ready. Next: `/verify $0`.
