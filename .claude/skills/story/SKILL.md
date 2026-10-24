---
name: story
description: Runs the full S5 pipeline for one story (spec -> spec review -> design -> failing tests -> implement -> verify -> review -> integrate) with the orchestrator delegating every stage to the roster subagent and enforcing the gates. Use when the owner says /story STORY-NNN or when /next picked a story; never let the author of a change verify or review it.
argument-hint: "STORY-NNN"
disable-model-invocation: true
user-invocable: true
---

# /story $0 — one story through the S2 lifecycle

You are the orchestrator (main session). You delegate; you write production code only for trivial glue.
Every subagent runs with the Agent tool, `subagent_type: <roster name>`, and the prompt built from the
template linked in each stage. Parse only the last `STATUS:` line of each report (the SubagentStop hook
guarantees it exists and is last). Any value other than the stage's pass value means "fix loop".

Supporting files: [prompts/](prompts/) (one template per agent), [triggers.md](triggers.md) (specialist
reviewer triggers + implementer routing), [worktree-merge.md](worktree-merge.md) (worktree subagents and
squash merging), [integrate.md](integrate.md) (stage 8 commands), [escalation.md](escalation.md).

## 0. Locate and lock
1. `STORY=$0`. Find the file: `git ls-files 'docs/backlog/*/STORY-'"${STORY#STORY-}"'-*.md' | head -1`
   (or `ls docs/backlog/*/${STORY}-*.md`). If none: stop with "story not found" and list the READY ones.
2. Read its frontmatter. `status` must be DRAFT, READY or IN_PROGRESS; BLOCKED/DONE/DESCOPED -> stop and
   say why. Every `depends_on` entry must be `status: DONE` -> otherwise stop and name the blocker.
3. `source scripts/env.sh`; `bash scripts/sdlc/state.sh set story "$STORY"`; `set stage spec`;
   `set loop_count 0`; `set phase <frontmatter phase>`.
4. Set `status: IN_PROGRESS` in the frontmatter (Edit). Append a PROGRESS line (format in
   [integrate.md](integrate.md)): `... · $STORY · spec · INFO · started`.
5. Branch: `SLUG=<slug from the file name>`; `git switch main && git pull --ff-only 2>/dev/null || true`;
   `git switch -c story/$STORY-$SLUG` (or `git switch` it if it exists); `BASE=$(git rev-parse main)`.
   Commit the status change: `docs($STORY): start [${STORY}]` (lefthook needs the `[STORY-NNN]` tag).
6. **Risk tier** (owner decision 2026-10-24; see CLAUDE.md Workflow). Read frontmatter `risk_tier`. If absent,
   decide it: `high` when the story's modules, REQ areas (PARSE, DEDUP, SMS, NTF, IOS, RCPT, WA, SEC) or touched
   paths involve parsing, dedup, money, auth or ingestion (incl. webhooks and LLM prompts); otherwise `low`.
   Write it into the frontmatter. **Low tier skips:** stage 1-2 step 2 (spec-reviewer), stage 3 unless the
   story names an open design decision, and stage 7 as a separate stage (see stage 6 low-tier rule).

## 1-2. Specification (only if status was DRAFT or the spec is incomplete)
1. Agent `product-analyst` with [prompts/product-analyst.md](prompts/product-analyst.md). Pass: story path,
   epic path, the Part I sections it implements, `docs/templates/story.md`. Expect `STATUS: SPEC_READY`.
2. **High tier only.** `state set stage spec-review`. Agent `spec-reviewer` with [prompts/spec-reviewer.md](prompts/spec-reviewer.md).
   Pass ONLY the story path and `docs/PROMPT.md` section refs; never the analyst's reasoning.
   `APPROVE` -> continue. `CHANGES_REQUESTED` -> back to product-analyst with the FINDINGS list verbatim.
   Max 2 spec loops; then [escalate](escalation.md) (reason: spec not converging) and stop this story.
3. Gate: Definition of Ready checklist in the story is fully ticked. Set `status: READY` if it was DRAFT.
   Commit `docs($STORY): spec [${STORY}]`.

## 3. Design
Low tier: skip unless the story names an open design decision; then a single architect pass, no ADR unless
significant. High tier: always.
1. `state set stage design`. Agent `architect` with [prompts/architect.md](prompts/architect.md).
   Pass: story path, `contracts/openapi.yaml`, module `CLAUDE.md`s, `docs/adr/` (next ADR number),
   `docs/templates/adr.md`, `docs/security/THREAT_MODEL.md`. Expect `STATUS: DESIGN_READY`.
2. If the design's `CONTRACT_IMPACT:` is not "none": `make contract-lint && make contract-diff BASE=main`.
   A breaking change needs an ADR named in the report, else send the architect back once.
3. If any path in the design or the story matches the `security-reviewer` row of [triggers.md](triggers.md):
   agent `security-reviewer` with [prompts/security-reviewer.md](prompts/security-reviewer.md) (design mode).
   `CHANGES_REQUESTED` -> architect fixes -> re-review (max 2), then escalate.
4. Commit `docs($STORY): design [${STORY}]` (plus `contracts/`, ADR files).

## 4. Test design
1. `state set stage tdd`. Agent `test-designer` (worktree) with [prompts/test-designer.md](prompts/test-designer.md).
   Pass: story path, branch name, the contract, the design notes, the make targets. NOT the implementation plan.
2. Expect `STATUS: TESTS_FAILING_AS_EXPECTED`. Its `COVERAGE_OF_CRITERIA:` must list every REQ in the story;
   a missing REQ or "compile error" failures -> send it back once with the gap list, then escalate.
3. Merge its worktree branch into the story branch per [worktree-merge.md](worktree-merge.md);
   commit `test($STORY): failing tests for REQ-... [${STORY}]`. Record `TESTS_SHA=$(git rev-parse HEAD)`.

## 5. Implementation
1. `state set stage implement`. Choose the implementer from `owner_agent`, else from `modules` via the routing
   table in [triggers.md](triggers.md). Multi-module stories: one implementer per module, in the order
   contract -> backend -> clients, each merged before the next starts.
2. Agent `<implementer>` (worktree) with [prompts/implementer.md](prompts/implementer.md). Pass: story path,
   branch, `TESTS_SHA`, design notes, make target `make check-fast MODULE=<m>`. On a verify/review fix loop
   also pass the verifier's/reviewers' FINDINGS verbatim (never your own paraphrase).
3. Expect `STATUS: READY_FOR_VERIFY`. Merge the worktree branch, commit
   `feat|fix(<scope>): <what> [${STORY}]`. Run `make check-fast MODULE=<modules>` yourself; red -> back to step 2.
4. Keep the report's `FILES_CHANGED:` and `KNOWN_LIMITATIONS:` ONLY to build the "Claims to verify" list.
   Never forward the implementer's summary or reasoning to the verifier or reviewers.

## 6. Verification
**Low tier:** ONE combined agent replaces stages 6 and 7: agent `qa-verifier` (worktree, fresh context) with
[prompts/qa-verifier.md](prompts/qa-verifier.md) plus the code-review checklist from
[prompts/code-reviewer.md](prompts/code-reviewer.md) appended ("also review the diff for correctness, design,
test quality and the CLAUDE.md invariants; report review findings in FINDINGS with severities"). Write both
`<story>.verify.md` and `<story>.review.md` from that one report (review report reviewer column:
`qa-verifier (combined, low tier)`); `PASS` with zero BLOCKER/MAJOR -> stage 8.
**High tier:** as follows.
1. `state set stage verify`. Agent `qa-verifier` (worktree, fresh context) with [prompts/qa-verifier.md](prompts/qa-verifier.md).
   Pass: story path, branch, `BASE`, `HEAD`, make targets, "Claims to verify:" (one line per REQ + each
   KNOWN_LIMITATION rewritten as a claim to test). Nothing else from the implementer.
2. Write `<story>.verify.md` yourself from the report using `docs/templates/verify-report.md`
   (verifiers have no Write tool). Commit `docs($STORY): verify report loop <n> [${STORY}]`.
3. `PASS` -> stage 7. `FAIL`/`BLOCKED` -> `loop_count=$(state get loop_count)+1`; `state set loop_count N`.
   If N > 3 -> [escalate](escalation.md), `status: BLOCKED`, `make check-fast`, clear state (step 8.7), stop.
   Else back to stage 5 step 2 with the FINDINGS list.

## 7. Review (parallel) — high tier only
1. `state set stage review`. In ONE message launch agent `code-reviewer` ([prompts/code-reviewer.md](prompts/code-reviewer.md))
   plus every specialist whose trigger in [triggers.md](triggers.md) matches `git diff --name-only $BASE...HEAD`
   ([prompts/specialist-reviewers.md](prompts/specialist-reviewers.md)). Same inputs as the verifier: story, diff
   refs, how to run, "Claims to verify". The review is of the diff, not the author.
2. Merge all FINDINGS into `<story>.review.md` (template `docs/templates/review-report.md`), one row per
   finding, sorted BLOCKER > MAJOR > MINOR > NIT. Commit `docs($STORY): review report loop <n> [${STORY}]`.
3. All `APPROVE` and zero BLOCKER/MAJOR -> stage 8. Otherwise this is a fix loop: it counts against the same
   `loop_count` (max 3) -> stage 5 with the findings; after fixing, re-run stage 6 (verify) then stage 7.
   MINORs/NITs not fixed: list them in the story's Status log as follow-ups; never silently drop them.

## 8. Integration
Merges to `main` go through a PR that you **squash-merge yourself** (owner decision 2026-10-25) once the gate is green
on the PR head, the verify report is PASS without open BLOCKER/MAJOR and you have read the diff. CI job `gate` must be green on the PR. Add the AI spend row and compare the
phase's running total with its cap in `docs/SDLC_METRICS.md` ("Phase budgets"); over the cap -> owner checkpoint.
Follow [integrate.md](integrate.md) exactly: rebase on main, `make check`, `make trace`, Conventional Commit
PR to `main` via `gh pr create` (squash-merged by you after the checks above), story `status: DONE` with a Status
log row, PROGRESS line `... · $STORY · integrate · GREEN · merged <sha>`, AI spend ledger row in
`docs/SDLC_METRICS.md`, then `state set story null; set stage done; set loop_count 0`.

## Exit gate
The skill is done only when: story file says `status: DONE`, `<story>.verify.md` ends `STATUS: PASS`,
`<story>.review.md` ends `STATUS: APPROVE`, `make check` and `make trace` were green on the merged commit,
PROGRESS and SDLC_METRICS were appended, and `.claude/state.json` has `story: null`. If any of these is false,
say so explicitly with the failing command output; never report "done" on a red gate.
