# Prompt template — `qa-verifier` (stage 6, worktree, fresh context)

Agent tool: `subagent_type: qa-verifier`. It gets the spec, the refs and how to run. It NEVER gets the
implementer's summary, reasoning or commit-message prose. "Claims to verify" is a list you build from the
REQ ids and from each KNOWN_LIMITATIONS line rewritten as a neutral, testable statement.

```
Verify one Masroof story adversarially. Assume it is broken until a run proves otherwise. You cannot edit files.

First command: `git merge --no-edit story/<STORY>-<slug>` so your worktree has the change ("Already up to date" is fine). Then `source scripts/env.sh` - in the same Bash call as each `make` (`source scripts/env.sh && make ... < /dev/null`), because make is only on PATH after it. If `make` still cannot start, run the target's Makefile recipe verbatim (`. scripts/env.sh && bash scripts/make/check.sh` for `make check`, `.../check-fast.sh <m>` for check-fast): the whole target, never one sub-step (STORY-141 loop 1 ran `npm run test:coverage` alone and missed the coverage-gate failure in `make check`).
Turn budget: batch independent commands into one Bash call. When about 10 turns remain, stop exploring and print the contract with what you observed; list unrun items as `NOT RUN` findings.

Story file: <path> (acceptance criteria REQ-<AREA>-NNN with Given/When/Then).
Branch: story/<STORY>-<slug>. Base: <BASE sha>. Head: <HEAD sha>. Verification loop: <n> of 3.
How to run: `make check-fast MODULE=<m>`; `make up` then curl http://localhost:<port>; `make e2e` for UI stories;
`python eval/runners/run.py --suite <suite> --fast` for parsing stories. Test-integrity:
`bash scripts/quality/test-integrity-check.sh <BASE>` and `.claude/state/test-integrity.log`.
Verify report template (for the shape of your findings): docs/templates/verify-report.md.

Claims to verify (each is untested until you observe it):
- REQ-<AREA>-001: <criterion in one line>
- REQ-<AREA>-002: <criterion in one line>
- <claim derived from a known limitation, e.g. "amounts above 1,000,000 EGP are forced to PENDING_REVIEW">

Procedure:
1. List every criterion and every implied edge case (Arabic-Indic digits, RTL, offline, duplicate delivery,
   Cairo midnight, month-start-day boundary, zero/negative/huge amounts, foreign currency, empty states).
2. Run `make check-fast MODULE=<m>`; record the summary line.
3. For each criterion: a real run (test, curl, Playwright/Maestro flow, eval case). Record `command` -> observed.
   PASS only when you observed it; "code looks right" is FAIL.
4. Exploratory: at least 10 inputs the tests do not cover; for parsing stories include prompt-injection text
   inside an SMS/receipt, malformed numbers, mixed languages.
5. Test integrity: tests deleted, skipped, loosened or mocked away since <BASE>? Read the log and the diff.
6. Do not fix anything. Do not accept claims. Report.

End your final message with exactly this contract (STATUS must be the last line):
STORY: <STORY>
CRITERIA:
- <REQ-AREA-NNN>: PASS | FAIL — evidence: `<command>` -> <result summary>
FINDINGS:
- [BLOCKER|MAJOR|MINOR|NIT] <file:line or flow> — <what is wrong> — <how to reproduce>
EXPLORATORY: <n> cases tried, <m> problems (<inputs>)
TEST_INTEGRITY: OK | VIOLATION (<details>)
STATUS: PASS | FAIL | BLOCKED
```

Parse: write `<story>.verify.md` from the report (template `docs/templates/verify-report.md`; fill Base/Head/
Loop/Environment from your variables). `PASS` with zero BLOCKER/MAJOR -> stage 7. `FAIL` -> fix loop:
`loop_count++`, implementer gets the FINDINGS verbatim. `BLOCKED` (environment, e.g. `make up` cannot start) ->
fix the environment as orchestrator glue and relaunch; it does not count as a loop unless the cause is the change.
`TEST_INTEGRITY: VIOLATION` is always at least MAJOR.
