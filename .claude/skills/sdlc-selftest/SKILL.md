---
name: sdlc-selftest
description: Proves every SDLC guard actually blocks by running scripts/sdlc/selftest.sh (plants each S15 violation on a throwaway branch or temp dir, observes the block, reverts) and reviewing docs/SDLC_SELFTEST.md so every S15 bullet is PROVEN. Use when the owner says /sdlc-selftest, at the end of Phase 0, and after any hook or gate change; reports RED unless all nine guards are proven.
argument-hint: "[--only <guard-name>]"
disable-model-invocation: true
user-invocable: true
---

# /sdlc-selftest $ARGUMENTS

The nine S15 guards that must each be PROVEN (a planted violation was BLOCKED, then reverted):
1. failing test -> `Stop` hook (`stop-gate.sh`) blocks completion
2. fake Anthropic key written to a file -> `scan-secrets.sh` blocks the write
3. edit of an applied migration (`V*__*.sql` on main) -> `protect-files.sh` blocks
4. `double amount` field in the backend domain -> ArchUnit fails `make check-fast MODULE=backend`
5. field removed from an OpenAPI response -> `make contract-diff BASE=main` (oasdiff) fails
6. prompt change that breaks Arabic-Indic digit cases -> eval gate fails (`python eval/runners/run.py --suite sms --adapter reference`)
7. injected-instruction receipt/SMS obeyed -> adversarial gate fails
8. subagent reply without `STATUS:` -> `SubagentStop` (`check-output-contract.sh`) blocks
9. `git push --force` to main -> `guard-bash.sh` blocks

## 1. Run the script (it does not need `make hooks-unlock`; it only observes blocks)
```bash
source scripts/env.sh
git status --porcelain            # must be empty; the script uses a throwaway branch and temp dirs
bash scripts/sdlc/selftest.sh $ARGUMENTS 2>&1 | tee "$SCRATCH/selftest.log"; echo "exit=$?"
git status --porcelain; git branch --list 'selftest/*'   # both must be empty afterwards (it reverts everything)
```
Leftover branch or dirty file -> the script failed to revert: clean up (`git switch main`, `git branch -D`,
`git checkout -- .`) only for files the log names, then mark that guard NOT PROVEN with the reason.
Never run the planted violations by hand against the real hooks with the Edit tool "to see what happens" —
the script drives the hooks with their JSON stdin exactly like Claude Code does.

## 2. Review `docs/SDLC_SELFTEST.md`
It must have one row per guard: violation planted, command, observed block (quoted stderr/exit code), revert
proof. For each of the nine, decide `PROVEN` (block observed) or `NOT PROVEN` (no block, wrong reason, tool
missing, or script skipped it). A guard whose row says "skipped: tool not installed" is NOT PROVEN; a passing
gate that should have failed is NOT PROVEN. Rows for guards 4-7 need the actual failing output of the make/eval
command, not only the script's own message.

## 3. Verdict
- All nine PROVEN -> GREEN. PROGRESS: `<ts> · P0 · SDLC · review · GREEN · sdlc-selftest 9/9 guards proven`.
  Commit `docs(sdlc): self-test proof [PHASE-0]`.
- Anything else -> RED. PROGRESS RED with the list of NOT PROVEN guards. For each: a READY story in the SDLC
  epic (`fix(sdlc): guard <n> does not block ...`) or an escalation if the fix needs the owner (missing tool).
  Phase 0 cannot be marked done (`/phase-gate 0` reads this file); do not edit `SDLC_SELFTEST.md` by hand to
  flip a result.
- Hook changes after a RED go through `/retro`-style unlock (`make hooks-unlock` ... `make hooks-test` ...
  `make hooks-lock`) by the devops-engineer story, then re-run this skill.

## Exit gate
You quote the nine verdicts, the script's exit code, and `git status --porcelain` empty. GREEN only at 9/9.
