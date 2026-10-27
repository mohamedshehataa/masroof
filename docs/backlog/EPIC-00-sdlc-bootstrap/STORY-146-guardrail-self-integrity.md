---
id: STORY-146
epic: EPIC-00
title: "Hooks keep their own integrity: HEAD-anchored self-check that fails closed, and no between-call baseline drift"
phase: 1
status: READY
size: M
modules: [sdlc]
depends_on: [STORY-001]
reqs: [REQ-OPS-168, REQ-OPS-169, REQ-OPS-173]
implements: ["PROMPT.md S7 (hooks)", "PROMPT.md S15 item 6 (every guard proven)", "STORY-001 accepted residual risk F-01, F-02"]
risk_tier: low
owner_agent: devops-engineer
---

# STORY-146 — Hooks keep their own integrity

## Goal
A single write to a hook file can no longer silently disable the guardrails (PROMPT S7). This closes STORY-001's accepted residual risks **F-01** and **F-02**. Number mapping: STORY-001's residual-risk list ↔ its loop-3 verify report.

| STORY-001 residual | verify loop 3 | severity | story |
|---|---|---|---|
| F-01 backstop sources `lib.sh` | F-03 | BLOCKER | this story, REQ-OPS-168 |
| F-02 shared snapshot | F-04 | MAJOR | this story, REQ-OPS-169 |
| F-03 B13 case-sensitive | F-01 | MAJOR | STORY-148, REQ-OPS-173 |
| F-04 backtick delete targets | F-02 | MAJOR | STORY-148, REQ-OPS-171 |

**Starting point.** The unverified commit `f54c33a` on local branch `story-001-loop4` (ref present in `.git/packed-refs`) was written for all four findings: a HEAD-anchored self-integrity check that fails closed, per-call snapshots, case-insensitive B13, and command-substitution delete targets. The implementer starts with `git show --stat f54c33a` and takes only the F-01/F-02 parts. Its tests are re-checked against the REQs below, not trusted. (The spec pass on 2026-10-25 had no shell, so the commit's diff was not read for this spec; the REQs come from the verify report's reproductions.)

## Definitions (used by both REQs)
- **Guard set G:** every `.claude/hooks/*.sh` file (12 today, including `lib.sh` and `restore-protected.sh`) plus `.claude/settings.json`.
- **Protected set P:** the REQ-OPS-011 set: G, `.claude/hooks/**`, `.claude/settings.local.json`, `docs/PROMPT.md`, `docs/PROMPT_DELIVERY_GATES.md`, `eval/baseline.json`, `.env`, `.env.*`.
- **Locked:** as in REQ-OPS-011. `hooks_unlocked` in `.claude/state.json` is not `true`, and `MASROOF_HOOKS_UNLOCK` is not set.
- **Temp project:** a git repo whose HEAD commit contains a copy of the real `.claude/hooks/` and `.claude/settings.json`. Its *own* hooks are invoked with `CLAUDE_PROJECT_DIR` pointing at it, as in the verify F-03 reproduction. Hook tests must use this setup, not the repo's hooks against a stub `lib.sh` (verify loop-3 gap: "test-restore-protected.sh runs the repo's hook rather than the project's own copy").

## Requirements and acceptance criteria
### REQ-OPS-168 — Hooks verify the guard set against git HEAD and fail closed
**Given** locked, a git work tree with a HEAD commit, and every file of G byte-identical to its HEAD blob **When** any hook runs **Then** its behaviour is unchanged:
- `make hooks-test` is green with no fewer assertions than before (619 hook cases at STORY-001 close);
- `guard-bash.sh` and `restore-protected.sh` each still finish in < 2 s per call on this repo (REQ-OPS-011 budget).

**Given** locked, a temp project, and exactly one file f of G changed after HEAD **When** the next `Bash` or `PowerShell` tool call reaches the hooks as wired in that project's `settings.json` (PreToolUse `guard-bash.sh` + `restore-protected.sh --snapshot`, then PostToolUse `restore-protected.sh`) **Then** at least one of the following holds, and the test asserts which one per file:
- (a) f is restored to its HEAD content, and the PostToolUse decision (`"decision": "block"`) names f; or
- (b) the call is blocked (exit 2). stderr has exactly one line that names f and tells the user to run `git checkout -- <f>` or `make hooks-unlock`. Every later call is blocked the same way until f equals HEAD again.

The test runs this for every file of G. Each file is changed three ways:
- content `x`;
- content `exit 0`;
- deletion.

It also runs for a new untracked file `.claude/hooks/evil.sh`, which is removed, as in REQ-OPS-011.

Mandatory reproduction (verify loop-3 F-03), in a temp project:
- `echo x > .claude/hooks/lib.sh`, then the project's own `guard-bash.sh` on `cat .env` → **exit 2** (today exit 0);
- after the next PostToolUse, `lib.sh` equals HEAD;
- the same with `restore-protected.sh` overwritten with `exit 0` → the next call is blocked (b) or restored (a), never silently allowed.

**Given** locked and `lib.sh` = `x`, `return 0` or `exit 0` **When** `protect-files.sh` receives a `Write` to `.env` **Then** exit 2 (the Write/Edit guard also fails closed).

The integrity check of a hook never depends on another file of G having integrity. It still works when `lib.sh` is the tampered file.
Unhappy path: **Given** hooks are unlocked (`make hooks-unlock` or `MASROOF_HOOKS_UNLOCK=1`) and files of G differ from HEAD **Then** nothing is restored or blocked for that reason, so hook development works as today.
Unhappy path: **Given** the owner runs `make hooks-lock` while files of G differ from HEAD (uncommitted hook edits) **Then** `make hooks-lock` exits non-zero, lists those files, says "commit or revert them first", and leaves hooks unlocked. Locking can never put the repo straight into the fail-closed state.
Unhappy path: **Given** the project is not a git work tree, or HEAD is unborn **Then** each hook prints exactly one stderr line `integrity unverifiable (no git HEAD)` and continues with its existing checks. The existing temp-dir hook tests keep their expectations.
Unhappy path: **Given** a `git switch` to a branch whose `.claude/hooks/guard-bash.sh` differs, run while locked **Then** the new HEAD is the reference, so nothing is restored or blocked. The REQ-OPS-011 "branch change, not a write" test stays green.
Non-goal, recorded as residual risk: one call that rewrites every hook wired to the same event, and edits that unwire hooks in `settings.json` before Claude Code reloads it. These are mitigated by Claude Code permission prompts, worktree isolation and reviewers diffing `.claude/**`.

### REQ-OPS-169 — Writes between calls or across overlapping calls are undone
**Given** locked and a temp project (verify loop-3 F-04 reproductions) **When** these sequences run **Then** the hooks behave as follows (today in both cases there is no decision and the file stays changed):
- (a) Between calls: pre, post, `echo hacked-between > .claude/hooks/guard-bash.sh` outside any call, pre, post → after the second post, `guard-bash.sh` equals HEAD, and one decision names it.
- (b) Overlapping calls in one checkout: pre(A), `echo hacked-A > .claude/hooks/guard-bash.sh`, pre(B), post(A), post(B) → after post(B), `guard-bash.sh` equals HEAD, and at least one of the two decisions names it.

**Given** the same sequences for `docs/PROMPT.md` and `eval/baseline.json` (tracked files of P outside G) **When** the file equalled HEAD when hooks were last locked, or there is no lock record **Then** it is restored to HEAD and named, as in (a) and (b).
**Given** a tracked file of P that already differed from HEAD when hooks were last locked (left over from an unlocked session) and that nothing changed since **Then** it is left as is. This keeps the REQ-OPS-011 "already modified before the call" bullet, now measured from lock time instead of from the previous call's snapshot.
**Given** an untracked file of P (`.env`, `.claude/settings.local.json`) changed during call A while call B's pre ran in between **Then** post(A) reports it as `changed (not restorable)`. A change to `.env` between calls, such as the owner editing it by hand, is **not** reported.
Unhappy path: **Given** 200 PreToolUse calls with no matching PostToolUse (crashed or cancelled calls) **Then** at most 20 per-call snapshot files remain in the state dir, and the next pre + post pair still takes < 2 s.
Unhappy path: **Given** a hook input without `tool_use_id` **Then** stderr has exactly one warning line, and the HEAD/lock-time comparison above still applies, so the hook errs toward restoring.
Unhappy path: **Given** malformed JSON **Then** behaviour stays as in REQ-OPS-011: exit 0 and exactly one stderr line.

### REQ-OPS-173 — The release flag cannot be switched on inline by an agent
**Given** `release_active` is false in `.claude/state.json` **When** an agent runs `MASROOF_RELEASE_ACTIVE=1 make bootstrap-host ...` (or any command that assigns `MASROOF_RELEASE_ACTIVE=` or `SSH_OPTS=` inline) **Then** guard-bash blocks it with one stderr line naming the rule, and `scripts/make/deploy-guard.sh` refuses unless `state.json` says `release_active` (the env flag is honoured only inside CI, `GITHUB_ACTIONS=true` set by the runner, as `release.yml` needs). Found by the PR #67 security review (THREAT_MODEL B9, row E).
Unhappy path: **Given** read-only diagnostics (`STEP=logs|db-recent`) **Then** they need their own `diag_active` flag, so diagnosing never arms the full release (which also unlocks raw ssh).

## UX states (UI stories only)
N/A

## Contract impact
None

## Data / migration impact
None. Hook state files under `.claude/state/` (gitignored) only.

## Security / privacy / LLM-safety impact
This story hardens the local guardrails, the mechanical backstop for every invariant in CLAUDE.md. The trust boundary moves out of the protected directory: the reference is git HEAD, not a file in `.claude/hooks/`.
- A fail-closed bug blocks every Bash call. Hence the `make hooks-lock` refusal and the "unverifiable → warn" rules.
- `.env` content is never copied, hashed into logs, or echoed.
- Risk tier is **low** by the CLAUDE.md definition (SDLC tooling). Because this closes a former BLOCKER, the combined verify+review agent must run the security-reviewer checklist, with probes built in a temp project as defined above.

## Eval impact
None

## Design notes (architect)
Low tier: no design stage unless the implementer needs a decision. Constraints from the REQs:
- the integrity check runs before `lib.sh` is sourced;
- the lock-time record is written by `make hooks-lock`;
- `.claude/hooks/**` edits need `make hooks-unlock` … `make hooks-lock`.

## Dependencies
STORY-001 (DONE with accepted risk). Independent of STORY-148, which touches only the guard-bash command analysis. If both are in flight, merge STORY-148 first (smaller).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines. Estimate ≈ 330:
  - integrity check outside `lib.sh` ~60, plus call sites in the 12 hooks ~25;
  - HEAD restore in restore-protected ~25;
  - per-call snapshots + lock-time record + pruning ~50;
  - `hooks-lock` refusal ~10;
  - temp-project tests ~160.

  F-03/F-04 were split into STORY-148 to stay under the cap. If f54c33a's F-01/F-02 part exceeds 400, split REQ-OPS-169 into its own story before /tdd.
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-24 | 1 intake | orchestrator | DRAFT created from STORY-001's accepted residual risk |
| 2026-10-25 | 1 spec (low tier) | product-analyst | READY — REQ-OPS-168/169 specified from the verify loop-3 reproductions (F-03, F-04 there); REQ-OPS-173/171 (F-03/F-04 here) split to STORY-148 for the size cap; risk_tier low with the security checklist in the combined review |
