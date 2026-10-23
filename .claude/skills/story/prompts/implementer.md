# Prompt template — implementers (stage 5, worktree)

Agent tool: `subagent_type:` one of `backend-engineer`, `ai-parsing-engineer`, `android-engineer`,
`web-engineer`, `devops-engineer` (routing in [triggers.md](../triggers.md)). On a fix loop include the
"Fix loop" block with the verifier's or reviewers' FINDINGS verbatim.

```
Implement one Masroof story so that the failing tests pass. Smallest change that satisfies the spec.

First command: `git merge --no-edit story/<STORY>-<slug>` (brings spec, design and the failing tests into your
worktree; "Already up to date" is fine). Then `source scripts/env.sh`.

Story file: <path> (spec + "Design notes (architect)").
Failing tests: commit <TESTS_SHA> on the story branch (`git show --stat <TESTS_SHA>`). Run them first and read the failures.
Module: <backend|web|android|infra>. Gate: `make check-fast MODULE=<m>` must be green before you report.
<ai-parsing-engineer only:> Also run `make eval-fast` (or `make eval SUITE=<suite>`) and report the deltas vs eval/baseline.json.
Conventions: CLAUDE.md, <module>/CLAUDE.md, .claude/rules/*.md (money as long minor units, Africa/Cairo,
immutable migrations, versioned prompts, no body/OCR/account logging above DEBUG, generated code untouched).
Knowledge skills load automatically when you touch money, SMS parsing, Arabic text or prompts; follow them.

Rules:
- Do not modify tests written by test-designer. If a test is wrong, leave it failing and explain under
  KNOWN_LIMITATIONS with the exact assertion; the verifier decides. (A hook logs any test edit you make.)
- No new dependency without a line in the story's design notes or an ADR; no `@Disabled`/`.skip`; no TODO
  without a story id; no `--no-verify`.
- Commit in your worktree with Conventional Commits ending in `[<STORY>]`.
- Report facts only: files, commands with their real output summary, limitations. No self-assessment of quality.

<Fix loop only:>
This is fix loop <n> of 3. Address every finding below (quote its id in your commit message body):
FINDINGS:
<paste the qa-verifier FINDINGS and/or reviewer FINDINGS verbatim>

End your final message with exactly this contract (STATUS must be the last line):
FILES_CHANGED:
- <path> — <one line>
COMMANDS_RUN:
- `<command>` -> <result summary>
<ai-parsing-engineer only:>
EVAL_RESULT:
- <suite>: <metric> <baseline> -> <now> (<gate>)
KNOWN_LIMITATIONS:
- <limitation or "none">
STATUS: READY_FOR_VERIFY | BLOCKED
```

Parse: `READY_FOR_VERIFY` -> merge the worktree branch, run `make check-fast MODULE=<m>` yourself, then
build "Claims to verify" from the story REQs plus each KNOWN_LIMITATIONS line turned into a testable claim.
`BLOCKED` -> if it names a test defect, send the exact assertion to the test-designer (one relaunch, the
test-designer may change its own tests); if it names a design gap, back to the architect; owner input -> escalate.
