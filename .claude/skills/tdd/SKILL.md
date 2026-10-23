---
name: tdd
description: Runs the test-design stage for one story (test-designer in a worktree writes REQ-tagged failing tests from the spec and contract only, proves they fail for the right reason, and the orchestrator squash-merges them onto the story branch). Use when the owner says /tdd STORY-NNN or from /story stage 4.
argument-hint: "STORY-NNN"
disable-model-invocation: true
user-invocable: true
---

# /tdd $0 — stage 4 (failing tests first)

## 0. Preconditions
- Story file has filled "Design notes (architect)" (else `/design $0`). Branch `story/$0-<slug>` checked out, clean.
- `source scripts/env.sh`; `bash scripts/sdlc/state.sh set story $0; ... set stage tdd`.
- Snapshot worktrees/branches per [../story/worktree-merge.md](../story/worktree-merge.md) "Before launching".
- List the REQ ids the tests must cover: `grep -oE 'REQ-[A-Z]+-[0-9]{3}' <story> | sort -u`.

## 1. Test designer — agent `test-designer` (worktree)
Prompt from [../story/prompts/test-designer.md](../story/prompts/test-designer.md): story path, branch, contract,
design notes, `.claude/skills/testing-conventions/SKILL.md`, make targets. Nothing about how to implement.
Expect `STATUS: TESTS_FAILING_AS_EXPECTED` with `TESTS`, `COVERAGE_OF_CRITERIA`, `COMMANDS_RUN`.

## 2. Gate the report before merging
1. Every REQ id from step 0 appears in `COVERAGE_OF_CRITERIA` as covered. Missing or `NOT COVERED` -> relaunch
   once with the exact gap list; still missing -> escalate (test gap is a spec or design problem).
2. `COMMANDS_RUN` shows the tests FAILING with assertion errors / 404 / "not implemented", not compile errors,
   missing beans, missing fixtures. Compile-error failures -> relaunch once ("make them compile with minimal
   stubs in src/test; do not implement behavior").
3. No test file outside the agent's write scope was touched (`git diff --name-only story/$0-<slug>...<wt-branch>`
   shows only test dirs, eval datasets, fixtures, test build config).

## 3. Merge and prove it yourself
Follow [../story/worktree-merge.md](../story/worktree-merge.md) "After the subagent returns":
squash into the story branch, commit `test(<scope>): failing tests for <REQ range> [$0]`.
Then run the tests on the story branch and record the summary, e.g.
`make check-fast MODULE=<m>` (expected RED here: the new tests fail) or the module's direct test command.
Record `TESTS_SHA=$(git rev-parse HEAD)` for `/implement`.

Note for the Stop hook: the gate is legitimately red between /tdd and /implement. Do not stop the session on
this branch; continue to `/implement $0` in the same session, or switch to `main` before stopping.

## 4. Record
- Story Status log row `| <date> | tdd | test-designer | TESTS_FAILING_AS_EXPECTED (<n> tests, <k> REQs) |`.
- PROGRESS line: `<ts> · P<phase> · $0 · tdd · INFO · <n> failing tests cover <k>/<k> REQs; TESTS_SHA <short sha>`.

## Exit gate
All REQs covered by tests that are proven to fail for the right reason, merged on the story branch as one
commit. Next: `/implement $0 TESTS_SHA=<sha>`.
