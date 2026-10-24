# PROMPT — Delivery gates: strict, measurable rules for push, PR, review, merge, release
*(Paste into the orchestrator session as an addendum to `docs/PROMPT.md`. It extends S2, S7, S9, S12 and S16. Where it conflicts with the original prompt, this file wins. Save it as `docs/PROMPT_DELIVERY_GATES.md`; protect it like `docs/PROMPT.md`.)*

## 0. Principle

Every transition of code from one place to another (working tree → commit → branch push → PR → approval → merge → tag → deploy) is a **gate with a numeric threshold and a machine check**. No gate is passed by assertion, judgement or "looks fine". A gate that cannot be checked by a script does not exist. Every threshold below is stored in one file, `scripts/quality/delivery-gates.yaml`, read by the hooks, by `make`, and by CI, so the three can never disagree.

Implement this as **STORY-145 "Delivery gates" (EPIC-00, Phase 0, READY)** with REQ-OPS-150..165 as numbered below, through the normal pipeline, before any story is merged to `main` after it lands. Until it lands, the orchestrator applies the rules by hand and records every manual check in the story's `.review.md`.

## 1. Metrics catalogue (`delivery-gates.yaml`)

| id | metric | threshold | measured by | gate |
|---|---|---|---|---|
| M1 | `make check-fast` for changed modules | exit 0, wall time ≤ 180 s | `scripts/make/check-fast.sh` | commit, push |
| M2 | `make check` | exit 0, ≤ 15 min | `scripts/make/check.sh` | PR, merge |
| M3 | Line / branch coverage, changed modules | ≥ thresholds in `quality-thresholds.yaml`; **no decrease vs `main`** of more than 0.5 pp | `coverage-gate.py --diff-base main` | PR, merge |
| M4 | Mutation score, money/parsing/dedup packages | ≥ 80 %; no decrease vs `main` | `coverage-gate.py --require-mutation` | merge (nightly for others) |
| M5 | Test integrity | 0 lost assertions, 0 new skip/disable/only, 0 assertion flips, 0 deleted test files without `--allow-deletions "<STORY-id>"` | `test-integrity-check.sh <base>` | commit, push, PR |
| M6 | Traceability | every REQ of the story has ≥ 1 passing test tagged with its id; 0 unknown REQ ids; 0 REQ of a DONE story without a passing test | `traceability.py --strict` | PR, merge |
| M7 | Contract | redocly 0 errors; oasdiff 0 breaking changes unless PR label `breaking-change` **and** an ADR path in the PR body | `contract.sh lint`, `oasdiff.sh` | PR, merge |
| M8 | Security — SAST/SCA/secrets | gitleaks 0 findings; semgrep 0 ERROR; osv-scanner 0 High/Critical with a known fix; trivy 0 High/Critical; 0 new suppressions without a `// reason:` and a STORY id | `make security` | PR, merge, release |
| M9 | Eval (AI-affecting diffs) | all `thresholds.yaml` gates pass; no metric drop > 1 pp vs baseline; OTP leak = 0; injection obeyed = 0; cost p95 rise ≤ 25 % | `make eval-fast` (PR), `make eval` (merge) | PR, merge |
| M10 | Verification | `.verify.md` exists for HEAD, `STATUS: PASS`, 0 BLOCKER/MAJOR findings, exploratory ≥ 10 cases, `TEST_INTEGRITY: OK`, verifier ≠ implementer | `delivery-gate.py verify` | PR, approval |
| M11 | Review | `.review.md` for HEAD with `STATUS: APPROVE` from code-reviewer **and** every triggered specialist; 0 open BLOCKER/MAJOR; every MINOR either fixed (commit sha) or ticketed (STORY id) | `delivery-gate.py review` | approval, merge |
| M12 | Size | ≤ 400 hand-written lines per story (excl. generated, wrapper, vendored); over → `size-exception` label + DECISIONS line | `delivery-gate.py size` | PR |
| M13 | Commit hygiene | every commit `type(scope): subject [STORY-NNN|SDLC|PHASE-N]`, subject ≤ 72, signed-off trailer present, no merge commits on the branch, branch rebased on `main` (0 commits behind) | `commit-msg-lint.sh`, `delivery-gate.py commits` | push, PR |
| M14 | Docs | story `status` ≠ DRAFT; `docs/PROGRESS.md` has a line for HEAD; DECISIONS line for every new dependency/version/suppression; ADR for every "significant" design note | `delivery-gate.py docs` | PR, merge |
| M15 | CI | all required checks green on the PR head sha; **no re-run of a flaky job without a quarantine entry**; flaky quarantine ≤ 5 | GitHub API via `delivery-gate.py ci` | approval, merge |
| M16 | Loop budget | verify+review loops ≤ 3; AI spend for the story ≤ 3× median of DONE stories (else DECISIONS line) | `.claude/state.json`, `SDLC_METRICS.md` | merge |
| M17 | Release | staging smoke exit 0; p95 API latency within S9 budgets (k6); Lighthouse ≥ 90/100/95; SBOM attached; images signed; rollback tested in the last 30 days | `make smoke`, `make perf`, `release.yml` | tag, deploy |
| M18 | Post-deploy | error rate < 1 % and parse-failure rate < 5 % for 30 min after deploy; else automatic rollback | `sre-observer`, `release.yml` | deploy |

## 2. Gate by gate (what runs, who may pass it, what blocks)

### G1 Commit (local, lefthook + hooks)
- `pre-commit`: gitleaks on staged files, shellcheck, formatter checks, **M5 on staged test files**, and `scan-secrets` on content.
- `commit-msg`: M13 format, plus trailer `Story: STORY-NNN` auto-added from the branch name.
- `pre-push` (new): M1 for changed modules, M5 vs `origin/main`, non-fast-forward to `main` rejected, push to `main` from a local checkout rejected (only PR merges create commits on `main`), untracked `*.verify.md`/`*.review.md` for the story must not exist (they are committed or absent).
- Block message always names the metric id and the command to re-run.

### G2 Push to a story branch (CI `pr.yml` on push, even before a PR exists)
- M1, M5, M6 (non-strict), M7, M8 (fast: gitleaks + semgrep + osv), M9 fast subset, M12, M13.
- Result posted as a **check run per metric** (one GitHub check named `gate/M5 test-integrity` etc.), never one aggregate check, so a reviewer sees exactly what failed.

### G3 Open a PR (`gh pr create` only via the `/story` skill, stage 8)
Preconditions checked by `delivery-gate.py pr-open` before `gh pr create` runs; the skill refuses otherwise:
- story `status: IN_PROGRESS`, branch `story/STORY-NNN-<slug>` matches the story id, rebased on `main`;
- `.verify.md` with `STATUS: PASS` for the exact HEAD sha (M10) — a PR is never opened for unverified code;
- `.review.md` with all APPROVE for HEAD (M11);
- M2 green locally (`make check`), M12, M14.
- PR body is **generated** from `.github/pull_request_template.md` and filled by the script: story link, REQ list with test names, verify/review report links, coverage before/after, eval delta, security scan summary, size, DECISIONS lines added, rollback plan. Hand-edited PR bodies are rejected by a `pr-body-lint` check (the body must contain the generated `<!-- gate:v1 sha=... -->` marker matching HEAD).
- Labels applied automatically: module(s), `security-review` when triggered paths changed, `breaking-change` only when oasdiff said so and an ADR exists.

### G4 Automated review on the PR (CI)
- Claude Code GitHub Action runs `code-reviewer` and, when labelled, `security-reviewer` **again** on the PR diff with fresh context, posting inline comments with severity tags. Its `STATUS:` line becomes check `gate/M11 ai-review`. CHANGES_REQUESTED blocks approval; it never replaces the local review stage.
- Every inline comment with `[BLOCKER]` or `[MAJOR]` must be resolved by a commit that references it (`Resolves: <comment-url>` trailer) or by a reply tagged `[WONTFIX STORY-NNN]`; `delivery-gate.py review` counts unresolved ones.

### G5 Approval
- Approval is a GitHub review with state APPROVED **from the owner's account or from the Action's `code-reviewer` run**; the orchestrator's own `gh pr review --approve` is denied by `guard-bash` (the author never approves).
- `delivery-gate.py approve` must be green: M10, M11, M15 (all required checks green on HEAD; a check that failed then passed on re-run without a quarantine PR is a violation), M16.
- Required checks on `main` (branch protection, applied by STORY-016): `gate/M1..M9`, `gate/M11 ai-review`, `gate/M12`, `gate/M13`, `gate/M14`, `pr-body-lint`. Required reviews: 1. Dismiss stale approvals on new pushes. Linear history. No force pushes. Admins included.

### G6 Merge
- Squash merge only, performed by `/story` stage 8 via `gh pr merge --squash --delete-branch` after `delivery-gate.py merge` passes: M2 (re-run on the merge base), M3 and M4 **with diff vs `main`**, M6 strict, M8 full, M9 full for AI diffs, M15, M16.
- Squash commit message = PR title (M13 format) + body trailers: `Story:`, `Reqs:`, `Verify:`, `Review:` (report paths + shas), `Co-Authored-By`.
- After merge: story `status: DONE` with a Status log row containing the merge sha; `docs/PROGRESS.md` line; `make metrics`; `make trace` committed if changed (as `[SDLC]`); branch deleted; `.claude/state.json` cleared. The post-merge `main.yml` run must be green within one run or the merge is **reverted automatically** by a `revert-on-red` job (a revert PR opened and auto-merged when its checks pass) and the story set back to IN_PROGRESS.

### G7 Release (`/release`)
- Preconditions: `main` green for the last 3 runs; nightly green within 24 h (M4 full, full eval, e2e, ZAP, MobSF, Lighthouse, k6); 0 open BLOCKER/MAJOR in any `.review.md` on `main`; `docs/SDLC_SELFTEST.md` GREEN within 7 days; restore test within 30 days (M17).
- Tag only from `main` HEAD; tag message generated from the changelog; images built from the tag, signed, SBOMs attached; staging deploy → `make smoke` → k6 → Lighthouse → **first prod deploy needs the owner's explicit "go" in chat (S16)**; later prod deploys are automatic when staging passes.
- Post-deploy (M18): `sre-observer` watches 30 min; breach → `make rollback` runs without asking, incident file from `docs/templates/incident.md`, story `/bug` opened.

### G8 Hotfix
- Branch `hotfix/<slug>` from the release tag; same gates as a story (verify by `qa-verifier`, review, M1..M8); regression test mandatory (M5 must show a net **increase** in assertions); squash to `main` and cherry-pick to the release branch; patch tag.

## 3. Enforcement map (every rule has a mechanism)

| rule | local hook | git hook (lefthook) | CI check | skill refusal |
|---|---|---|---|---|
| no push to main | guard-bash | pre-push | branch protection | — |
| no self-approval | guard-bash (`gh pr review --approve` denied) | — | required reviews | /story |
| no PR without verify PASS | — | — | `gate/M10` | /story pr-open |
| no merge with red metric | — | — | required checks | /story merge |
| no hand-edited PR body | — | — | `pr-body-lint` | — |
| no silent flaky re-run | — | — | `gate/M15` (compares run attempts) | — |
| no unlogged suppression | protect-files + scan | pre-commit grep | `gate/M8` | — |
| no secret anywhere | scan-secrets | gitleaks | gitleaks + push protection | — |
| no skipped/weakened test | test-integrity | pre-commit M5 | `gate/M5` | — |
| loop ≤ 3 | stop-gate counter | — | `gate/M16` | /story escalate |

## 4. Deliverables for STORY-145

- `scripts/quality/delivery-gates.yaml` (all thresholds; the single source), `scripts/quality/delivery-gate.py` with sub-commands `pr-open | approve | merge | verify | review | size | commits | docs | ci`, each printing a metric table and exiting 0/1, with unit tests (fixtures: a passing PR, each metric failing once).
- `scripts/quality/pre-push-guard.sh` extended with M1/M5; lefthook `pre-push` wired.
- `.github/workflows/pr.yml` split into one check per metric (`gate/Mn <name>`), `pr-body-lint`, `revert-on-red` job in `main.yml`, `flaky-guard` step.
- `.github/pull_request_template.md` regenerated with the `<!-- gate:v1 -->` marker and the auto-filled sections.
- `guard-bash.sh`: deny `gh pr review --approve`, `gh pr merge` outside `/story`, `git push origin main`, `gh pr create` outside `/story` (state flag `pr_flow_active`).
- `/story` stage 8 and `/release` updated to call `delivery-gate.py` and to refuse on red.
- `docs/DELIVERY_GATES.md` (human version of §1–§3) and a `make gate STAGE=pr-open|approve|merge` target.
- `make selftest` gains rows proving: a PR opened without a verify report is refused; a self-approval is blocked; a weakened test fails `gate/M5`; a hand-edited PR body fails `pr-body-lint`; a push to main is rejected.

## 5. Definition of Done for this prompt

All §4 deliverables exist, `docs/SDLC_SELFTEST.md` shows the five new rows PROVEN, STORY-145 itself was merged through G1–G6 with every `gate/*` check visible and green on its PR, and `docs/DELIVERY_GATES.md` is linked from `CLAUDE.md` under Workflow.
