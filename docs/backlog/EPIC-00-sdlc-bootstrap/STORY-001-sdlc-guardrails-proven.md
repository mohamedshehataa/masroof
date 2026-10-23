---
id: STORY-001
epic: EPIC-00
title: SDLC guardrails are proven
phase: 0
status: READY
size: M
modules: [sdlc]
depends_on: []
reqs: [REQ-OPS-001, REQ-OPS-002, REQ-OPS-003, REQ-OPS-004, REQ-OPS-005, REQ-OPS-006, REQ-OPS-007, REQ-OPS-008, REQ-OPS-009, REQ-OPS-010]
implements: ["PROMPT.md S7", "PROMPT.md S15 item 6", "PROMPT.md S2 (loop limit)", "PROMPT.md S16"]
owner_agent: devops-engineer
---

# STORY-001 — SDLC guardrails are proven

## Goal
Every hook wired in `.claude/settings.json` demonstrably blocks or warns on the violation it exists for, so the product can be built through the SDLC with the guards as the approval (PROMPT S7, S15 item 6). Evidence is the hook test suite (`make hooks-test`) plus `docs/SDLC_SELFTEST.md`.

## Requirements and acceptance criteria
### REQ-OPS-001 — guard-bash blocks destructive and secret-leaking commands
**Given** a `PreToolUse` Bash event on the `main` branch **When** the command is `git push --force origin main`, `git reset --hard`, `rm -rf` outside a worktree, a `curl ... | sh` pipe-to-shell, `cat .env`, `printenv ANTHROPIC_API_KEY`, or an `ssh`/`psql` to the production host named in `.env` **Then** the hook exits 2 and stderr names the matched rule and the safe alternative.
**Given** the same event on a `story/STORY-xxx` branch **When** the command is `git push --force-with-lease origin story/STORY-xxx` or `rm -rf build/` **Then** the hook exits 0 (allowed).
Unhappy path: **Given** the hook receives malformed JSON on stdin **When** it runs **Then** it exits 0 with a one-line warning on stderr and never blocks on a parse error.

### REQ-OPS-002 — protect-files blocks protected paths and enforces role write scopes
**Given** an `Edit`/`Write` event **When** the target is `.env`, `*.pem`/`*.jks`/`*.keystore`, a Flyway migration already listed as applied in the migration ledger, a path under `**/generated/**`, `eval/baseline.json`, `.claude/hooks/**`, `.claude/settings.json`, or `docs/PROMPT.md` **Then** the hook exits 2 and stderr says why (the migration message contains "immutable"; the hooks message mentions `hooks-unlock`).
**Given** `.claude/state.json` has `hooks_unlocked: true` **When** `.claude/hooks/guard-bash.sh` is edited **Then** the edit is allowed.
**Given** the event carries `agent: product-analyst` **When** the target is `backend/src/main/java/X.java` **Then** exit 2 (outside write scope `docs/*`); **When** the target is `docs/backlog/EPIC-01/STORY-009.md` **Then** exit 0.
Unhappy path: **Given** a new, not-yet-applied migration `V7__x.sql` **When** edited **Then** exit 0 (only applied migrations are immutable).

### REQ-OPS-003 — scan-secrets blocks secret-like content in writes
**Given** a `Write`/`Edit` event whose content contains an Anthropic-style key (`sk-ant-` + 20+ chars), an AWS access key id, a JWT, a private-key PEM header, or a Meta app secret assigned to `WHATSAPP_APP_SECRET=` **When** the hook runs **Then** it exits 2 and stderr names the detector but never echoes the secret itself.
**Given** content with `ANTHROPIC_API_KEY=changeme` in `.env.example` or the token `sk-ant-...` inside a Markdown code fence documenting the format **When** the hook runs **Then** exit 0 (placeholders allowlisted).
Unhappy path: **Given** a 5 MB write **When** scanned **Then** the hook finishes in < 2 s and still detects a key at the end of the content.

### REQ-OPS-004 — stop-gate blocks stopping on a red check-fast and escalates after 3 blocks
**Given** a `Stop` event with a clean tree or a docs-only change **When** the hook runs **Then** `make check-fast` is not invoked and exit is 0.
**Given** changed files under `backend/` and `make check-fast MODULE="backend"` exits non-zero **When** the hook runs **Then** exit 2, stderr contains `block 1/3`, the module name, and the last 20 lines of the failure (e.g. `ExpenseTest`), and `.claude/state.json.stop_blocks == 1`.
**Given** two prior consecutive blocks **When** a third red run happens **Then** the hook emits a `systemMessage`, appends a `RED` line to `docs/PROGRESS.md` and a `GATE-` entry to `docs/ESCALATIONS.md`, resets `stop_blocks` to 0 and allows the stop.
**Given** a green run recorded as `last_green` fingerprint of the tree **When** Stop fires again with an unchanged tree **Then** make is not re-run; **When** a file changed **Then** make runs again. Changed files in `web/` and `.claude/` run `MODULE="sdlc web"` (sorted).

### REQ-OPS-005 — SubagentStop enforces the output contract
**Given** a `SubagentStop` event for a roster agent (e.g. `qa-verifier`) whose last message lacks `STATUS:` or has a STATUS value outside `.claude/hooks/output-contracts.json` **When** the hook runs **Then** exit 2 and stderr lists the allowed statuses (`PASS|FAIL|BLOCKED`) and the required sections (`CRITERIA:`), naming the missing one.
**Given** the message ends with the exact contract (sections present, `STATUS: PASS` last line) **Then** exit 0.
Unhappy path: **Given** an agent name not in the contracts file **When** the hook runs **Then** exit 0 with a warning (non-roster agents are not gated).

### REQ-OPS-006 — SessionStart injects context
**Given** a `SessionStart` event (source `startup|resume|compact`) **When** the hook runs **Then** stdout contains: current phase (`Phase: 2`), active story id and stage (`STORY-001`, `implement`), loop counter `1/3`, the last 20 lines of `docs/PROGRESS.md` (not line 5 of a 30-line file), `Open escalations (n)` counting only unresolved entries, the Invariants section of `CLAUDE.md` (stopping at the next heading), backlog counts by status (`READY 1`, `DONE 1`), and `SessionStart: <source>`; total output < 10,000 bytes.
Unhappy path: **Given** `docs/PROGRESS.md` is missing **When** the hook runs **Then** it prints `PROGRESS.md: none` and exits 0.

### REQ-OPS-007 — test-integrity warns on weakened tests
**Given** a `PostToolUse` Edit on a test file whose assertion count (`assert*`, `expect(`, `shouldBe`, `@Test` methods) did not decrease and no `@Disabled`/`.skip`/`@Ignore`/`xit(` was added **When** the hook runs **Then** stdout is empty.
**Given** assertions dropped from 3 to 1 or a skip marker was added by agent `backend-engineer` **Then** stdout contains `TEST INTEGRITY WARNING` with the old count, and `.claude/state/test-integrity.log` gains a line naming the agent, file and delta for `qa-verifier`.
Unhappy path: **Given** the edited file is not a test file **Then** no output and no log line.

### REQ-OPS-008 — budget-check warns when over budget
**Given** `.env` sets `SDLC_AI_BUDGET_USD=300` and the `AI spend (USD)` row of `docs/SDLC_METRICS.md` reads `350` **When** the `UserPromptSubmit` hook runs **Then** stdout contains `BUDGET EXCEEDED` and the spend `350`, and exit is 0 (warn, not block — S16 checkpoint 3).
**Given** spend `120` **Then** no output.
Unhappy path: **Given** `docs/SDLC_METRICS.md` is missing or the spend cell is non-numeric **Then** the hook exits 0 silently; **Given** the cell reads `1,234.50` or `$350` **Then** it is parsed as 1234.50 / 350 (separators and currency signs stripped) and compared correctly.

### REQ-OPS-009 — PreCompact snapshot preserves state
**Given** a `PreCompact` event with trigger `auto` **When** the hook runs **Then** `.claude/state.json` records `last_compact` (ISO timestamp containing `T`) and `last_compact_trigger: auto`, keeps existing keys (e.g. `story: STORY-001`), and exactly one `state-<timestamp>.json` snapshot is written under `.claude/state/snapshots/`.
Unhappy path: **Given** the snapshots directory is not writable **Then** stderr explains and exit is 0 (compaction is never blocked).

### REQ-OPS-010 — format-changed gives lint feedback
**Given** a `PostToolUse` Write/Edit on `*.java`, `*.kt`, `*.ts/tsx`, `*.sh`, `*.py` or `*.yaml` **When** the hook runs **Then** it runs the module formatter/linter for that one file (spotless/ktlint/prettier+eslint/shellcheck/ruff-or-pyflakes/yamllint) in < 10 s and prints findings as `file:line: message` on stdout; a clean file prints nothing.
Unhappy path: **Given** the linter binary is absent **Then** one line `format-changed: <tool> not installed, skipped` and exit 0.

## UX states (UI stories only)
N/A (no UI).

## Contract impact
None — SDLC tooling only.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
These hooks are the mechanical guard for the Invariants in `CLAUDE.md` (secrets only in `.env`, applied migrations immutable, generated code untouched, tests never weakened). REQ-OPS-003 must never print the detected secret. Hook edits require `make hooks-unlock` (logged).

## Eval impact
None directly; the eval gate is proven by `/sdlc-selftest` (S15 item 6, recorded in `docs/SDLC_SELFTEST.md`), not by this story.

## Design notes (architect)
Filled at stage 3.

## Dependencies
None (first story of Phase 0).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (hooks exist; this story is the test suite + fixes)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY — REQ ids match `.claude/hooks/tests/test-*.sh` headers |
