# Prompt template — `test-designer` (stage 4, worktree)

Agent tool: `subagent_type: test-designer`. It receives the spec, the contract and the design notes, never an
implementation plan or existing implementation hints beyond what is on the branch.

```
Write the FAILING tests for one Masroof story. Tests first; no implementation code.

First command: `git merge --no-edit story/<STORY>-<slug>` so your worktree has the spec and design
("Already up to date" is fine). Then `source scripts/env.sh`.

Story file: <path> — every REQ-<AREA>-NNN with its Given/When/Then is a test to write.
Contract: contracts/openapi.yaml (for contract tests and generated client types).
Design notes: the "Design notes (architect)" section of the story (module boundaries and key types only).
Conventions: .claude/skills/testing-conventions/SKILL.md (REQ tagging per framework, jqwik patterns,
Testcontainers, Roborazzi matrix, Playwright + axe, Maestro) and the module CLAUDE.md.
Make targets: `make check-fast MODULE=<backend|web|android|sdlc>`.

Rules:
- Tag every test with its REQ id (JUnit `@Tag("REQ-SMS-004")`, Vitest/Playwright `describe('REQ-SMS-004 ...')`,
  Kotlin test name prefix, Maestro flow name, eval case `tags`). `make trace` fails on untagged or unknown ids.
- Prefer property-based tests (jqwik) for money, normalization, dedup and date math; Testcontainers Postgres
  for anything touching the DB; contract tests for new endpoints; eval cases in eval/datasets/<suite>.jsonl
  for parsing behavior (append, with `source: synthetic`).
- Assert behavior, never log text or private internals. No mocks that hide the behavior under test.
- RUN the tests. They must fail with assertion errors or "endpoint returned 404", not with compile errors,
  missing beans or missing fixtures. If the only way to make them compile is a stub interface/type, create the
  minimal stub in src/test or ask for it under BLOCKED; never implement behavior.
- Commit in your worktree: `test(<scope>): failing tests for <REQ list> [<STORY>]`.

End your final message with exactly this contract (STATUS must be the last line):
TESTS:
- <file>: <test names> (REQ ids)
COVERAGE_OF_CRITERIA:
- <REQ-AREA-NNN>: covered by <test names> | NOT COVERED (<reason>)
COMMANDS_RUN:
- `<command>` -> <n failed, m passed, failure reason excerpt>
STATUS: TESTS_FAILING_AS_EXPECTED | BLOCKED
```

Parse: `TESTS_FAILING_AS_EXPECTED` and every story REQ appears as "covered" -> merge the worktree branch
([worktree-merge.md](../worktree-merge.md)). A `NOT COVERED` REQ or a compile-error failure reason -> relaunch
once with the gap list; then escalate. `BLOCKED` asking for a stub type -> architect adds it to the design
(allowed in `*/src/test/*`), then relaunch.
