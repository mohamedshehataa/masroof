---
name: bug
description: Handles a reported defect end to end (qa-verifier reproduces it, test-designer writes a failing regression test tagged with a REQ id, the right implementer fixes it, verify and review run as for a story, a LESSONS.md entry records the root cause class). Use when the owner says /bug "<text>" or a defect escapes a DONE story; every fix must carry its regression test.
argument-hint: "\"<what happens, where, expected vs actual>\""
disable-model-invocation: true
user-invocable: true
---

# /bug $ARGUMENTS

A bug is a story with a reproduction first. Same gates, same loop limit (3), same evidence rule.

## 1. Reproduce — agent `qa-verifier` (worktree, fresh)
Prompt (adapt [../story/prompts/qa-verifier.md](../story/prompts/qa-verifier.md)):
"Reproduce this reported defect, do not fix it: `<text>`. Find the owning story (`grep -rl <keyword> docs/backlog`)
and the REQ it violates, or state that no REQ covers it. Produce the smallest deterministic reproduction
(command, request, input text, screen flow) and the observed vs expected result. Try 5 neighbouring inputs to
find the boundary. Contract: STORY (owning story or NONE), CRITERIA (the violated REQ with evidence), FINDINGS,
EXPLORATORY, TEST_INTEGRITY, STATUS: FAIL (reproduced) | PASS (not reproducible) | BLOCKED."
- `PASS` (cannot reproduce) -> record in PROGRESS as INFO with the attempts, ask the owner for more detail in
  one line, stop.
- `FAIL` -> continue with its reproduction as the spec.

## 2. Create the bug story
`docs/backlog/<owning epic or EPIC-99-bugs>/STORY-NNN-fix-<slug>.md` from `docs/templates/story.md`:
`title: Fix: <one line>`, `status: READY`, `size: S`, `modules`, `owner_agent` by module, `reqs`: the violated
REQ id (reuse it; a new `REQ-<AREA>-NNN` only when no REQ existed, then also add it to the owning story's spec),
Goal = the reproduction; acceptance criterion = Given the reproduction input When ... Then expected result;
"Security/privacy" and "Eval impact" filled (parsing bugs always add an eval case). Skip spec-reviewer for
size S bugs with an existing REQ; run it when a new REQ was written.
`bash scripts/sdlc/state.sh set story STORY-NNN; set stage tdd; set loop_count 0`; branch `story/STORY-NNN-fix-<slug>`;
commit `docs(STORY-NNN): bug report [STORY-NNN]`.

## 3. Regression test — agent `test-designer` (worktree)
Prompt from [../story/prompts/test-designer.md](../story/prompts/test-designer.md) plus: "This is a regression test
for a reproduced defect; the reproduction is in the story. Write the smallest test at the lowest level that
catches it (unit or property first; integration/e2e only if the defect lives in wiring), tagged with the REQ id,
plus an eval case if parsing is involved. It must fail now with the observed wrong value in the assertion message."
Expect `TESTS_FAILING_AS_EXPECTED`; merge per [../story/worktree-merge.md](../story/worktree-merge.md);
commit `test(<scope>): regression for <REQ> [STORY-NNN]`.

## 4. Fix, verify, review
Run `/implement STORY-NNN`, then `/verify STORY-NNN` (claims: the regression criterion + "no other test changed"),
then `/review STORY-NNN` (code-reviewer + triggered specialists). Loop limit 3 -> escalate. Then stage 8 per
[../story/integrate.md](../story/integrate.md) with commit type `fix`.

## 5. Learn (mandatory, before DONE)
Append to `docs/LESSONS.md`:
`- <date> — <what happened, one line> -> <root cause class: spec gap | test gap | review miss | environment> (<one-line why>) -> <prevention: the regression test path + any rule/hook/checklist/agent-prompt change>.`
If the root cause is an agent's behavior, also note the agent name so `/retro` updates its prompt. If the bug
escaped a DONE story, add a Status log row to that story: `| <date> | escaped defect | orchestrator | STORY-NNN |`
and increment the "escaped defects" count on the next `make metrics`.

## Exit gate
Fix merged with its regression test in the same squash commit, verify PASS, review APPROVE, LESSONS entry
present, PROGRESS line `... · STORY-NNN · integrate · GREEN · bug fixed; regression <test name>; root cause <class>`.
