# Retro — Phase 0

- **Date:** 2026-10-25  ·  **Facilitator:** retro-facilitator
- **Inputs:** [phase-0-report](../phases/phase-0-report.md) (GREEN), docs/SDLC_METRICS.md (committed at cbcad1f), docs/ESCALATIONS.md, docs/LESSONS.md, docs/PROGRESS.md (P0 lines), the 7 verify and 6 review reports in `docs/backlog/EPIC-00-sdlc-bootstrap/`, `.claude/state/hooks.log` and `test-integrity.log` (main checkout)
- **Scope rule (owner, 2026-10-24/25):** remove friction, fix wrong instructions, record lessons. No new gates, hooks, agents, stories or machinery. Anything bigger is listed under Proposals and is not applied.

## Metrics snapshot
| stories done | median cycle | loops/story | findings (B/M/m/NIT) | escaped defects | gate failures | AI spend |
|---|---|---|---|---|---|---|
| 7 (+1 DESCOPED) | 1.0 d | 0.33 (metric); verify FAIL loops from PROGRESS: 001 x3, 141/142/145 x1 each | 1/13/57/66 | 4 (LESSONS:11, 14, 16) | 51 = 10 RED PROGRESS + 41 hook BLOCKs | USD 135 ledger (cap 200) |

- Spend per story: median USD 14 (002: 38, 142: 16, 141: 14, 143: 12, 145: 6). None is over 3x the median (42). STORY-002 is the highest at 2.7x: it was the first story through the full pipeline (2 spec loops, 1 security fix loop, 3 reviews).
- Hook BLOCKs (41): stop-gate 13 (two were 540 s timeouts before check-fast sdlc dropped to 68 s), scan-secrets 14 (most were hook tests and selftest probes), guard-bash 9 (1 false positive: `DROP/TRUNCATE ... host 'ealth-version'`, 2026-10-23, before the parser rewrite), protect-files 5 (3 were docs-writer writing README.md out of scope). Test-integrity warnings: 6, all on one quality-test file on 2026-10-23, all explained.
- Flaky tests quarantined: 0. One one-off Gradle `NoSuchFileException` in `check-full` did not reproduce (phase report, Notes).
- No previous retro, so there are no Try items to measure.

## Keep
- **Fresh-context adversarial verification.** STORY-001 loop 1 found 11 MAJOR bypasses in hooks unit-tested at 242/242. STORY-142 loop 1 found that `make e2e` could not be green (port 80 clash). STORY-145 loop 1 found that the gate passed when `detect` failed.
- **Low-tier combined verify+review.** 141/142/143/145 cost USD 6-16 each, against USD 38 for the full-pipeline STORY-002. Every one reached PASS + APPROVE in 2 loops or fewer.
- **An implementer stops BLOCKED instead of editing a test** (LESSONS 2026-10-24, junit metadata criterion). Test integrity: 0 violations in 7 stories.
- **Owner-light decisions.** OWNER-011 and OWNER-014 were closed with "do whatever asks me the least", and the phase finished inside its cap (USD 135-137 of 200, 2 days before the 2026-10-27 deadline).

## What hurt (evidence) -> Change
1. **Verifiers could not run `make`, so they ran a sub-step and missed a gate.** STORY-141 verify loop 1 (line 12: "make binary was refused") ran `check-fast.sh web` and `npm run test:coverage`, but not `scripts/make/check.sh`. The web coverage gate needed `coverage-summary.json`, and the orchestrator found that out later and fixed it (36cca85). STORY-142 verify line 4 says the same: "make is not on PATH in the sandbox". Cause: make is on PATH only after `scripts/env.sh`, and each Bash call starts a fresh shell. This retro reproduced it: `source scripts/env.sh | tail` ran in a subshell and then gave `make: command not found`. -> Run `source scripts/env.sh && make ...` in one call. If make still cannot start, run the Makefile recipe verbatim, for the whole target. Applied in `.claude/agents/qa-verifier.md`, `.claude/skills/story/prompts/qa-verifier.md` and `.claude/skills/story/worktree-merge.md`.
2. **Wrong instruction about lefthook in worktrees.** `worktree-merge.md` said "lefthook runs there too". LESSONS 2026-10-24 shows it does not ("Can't find lefthook in PATH"). -> Corrected: the hooked commit is the orchestrator's squash commit in the main checkout. Applied in `.claude/skills/story/worktree-merge.md`.
3. **Stacked PRs merged into their base branches** (#3/#4, re-PR #8). This was already fixed in `.claude/skills/story/integrate.md` (`--base main`), so no change here.
4. **Turn limits hit.** The ledger records STORY-143's combined verify+review "resumed once at turn limit". A second hit, reported by the orchestrator, is not in any report. The low-tier combined agent does both verify and review inside qa-verifier's 60 turns. -> `maxTurns` 60 -> 80, the same as the implementers. Added a turn-budget line: batch commands, and with about 10 turns left print the contract with the unrun items marked `NOT RUN`. Applied in `.claude/agents/qa-verifier.md` and `.claude/skills/story/prompts/qa-verifier.md`.
5. **Line endings on `backend/gradlew.bat`.** Fixed by 5b6276a. `git ls-files --eol` now shows `i/lf w/crlf attr/text eol=crlf`, which is correct, so no change.
6. **Windows vs Linux CI.** PR #2 went red on Linux (verification metadata, yamllint wording, gitleaks-action, fixed in 3ec0bf8). STORY-143 needed `-PgitSha` in a git-less container. STORY-142 needed Linux screenshot baselines. -> One instruction line in each module file. Applied in `backend/CLAUDE.md` and `web/CLAUDE.md`.
7. **Size estimates 2-2.7x over.** STORY-002 812 vs 388; STORY-141 1059 vs 395 (141 review, MINOR). LESSONS 2026-10-24 already recorded the 2x rule for test code, but it was never written into an agent. -> Applied in `.claude/agents/architect.md` and `.claude/agents/product-analyst.md`.
8. **The spec-review loop limit was hit twice** (LOOP-001, LOOP-003), and both times the fix was to apply the loop-2 proposals verbatim. Both escalations asked this retro to decide. -> Made that the documented rule when loop 2 has no BLOCKER and every finding has proposed text, so no escalation is needed. This removes an escalation; it adds none. Applied in `.claude/skills/spec/SKILL.md`.
9. **`make metrics` in a fresh worktree erases data.** In this retro it rewrote gate failures 51 -> 10 and coverage to "no report", because `.claude/state/` and the build reports are gitignored. The regeneration was reverted, not committed. -> Applied in `.claude/skills/retro/SKILL.md`.

## Try
- qa-verifier at 80 turns with the turn-budget line. Measured by: turn-limit resumes in the Phase 1 ledger notes (target 0). Owner: orchestrator ledger.
- The full-recipe fallback for `make`. Measured by: Phase 1 gate failures that a verifier PASS missed (target 0; Phase 0 had 1, STORY-141). Owner: qa-verifier.
- The 2x test-line estimate. Measured by: actual/estimate size ratio in Phase 1 reviews (target <= 1.3x; Phase 0 was 2.1-2.7x). Owner: architect, product-analyst.

## Proposals (need owner decision; not applied)
- Put make (GnuWin32) and lefthook on the PATH that every agent shell inherits, via `env` in `.claude/settings.json` or a user-level install. This would remove items 1 and 2 at the root. It is a settings change, so it waits for your decision.
- Count the 400-line story cap on production code only, so test scaffolding does not force splits. This changes a Definition-of-Ready threshold, so it needs your decision.
- Pull STORY-016's "CI lints commit messages and runs gitleaks per PR" forward, so worktree commits that skip lefthook are caught before main. That changes the order of an existing story; no new story is needed.

## Applied changes (each with reason)
| file | change | reason |
|---|---|---|
| `.claude/agents/qa-verifier.md` | make-in-one-call + full-recipe fallback sub-bullet; `maxTurns` 60 -> 80 | STORY-141 loop 1 missed the coverage gate; STORY-143 turn-limit resume |
| `.claude/skills/story/prompts/qa-verifier.md` | same fallback in "First command"; turn-budget line | same evidence |
| `.claude/skills/story/worktree-merge.md` | env.sh + make in one call, fallback; corrected "lefthook runs there too" | wrong instruction vs LESSONS 2026-10-24 |
| `.claude/agents/architect.md`, `.claude/agents/product-analyst.md` | estimate test code at ~2x | 2-2.7x size overruns (002, 141); lesson never applied |
| `backend/CLAUDE.md` | regenerate verification metadata on Linux too | PR #2 Linux red (3ec0bf8); STORY-143 git-less container |
| `web/CLAUDE.md` | screenshot baselines from the Linux Playwright image | STORY-142 Linux baselines |
| `.claude/skills/spec/SKILL.md` | after spec loop 2, apply proposals verbatim when there is no BLOCKER and all findings have proposed text | LOOP-001 and LOOP-003 asked the retro to decide; removes escalations |
| `.claude/skills/retro/SKILL.md` | do not commit `make metrics` regenerated in a worktree | this retro: 51 -> 10 gate failures, coverage lost |
| `.claude/agent-memory/product-analyst/*` | pruned stale "M1-M18 rules" index line and "branch protection in force" | STORY-147 DESCOPED, addendum deferred |

STATUS: RETRO_DONE
