---
id: STORY-145
epic: EPIC-00
title: "CI on every PR and branch protection on main (delivery gates, cut down)"
phase: 0
status: DONE
size: S
modules: [sdlc]
depends_on: [STORY-001]
reqs: [REQ-OPS-150]
implements: ["PROMPT_DELIVERY_GATES.md §0 (cut down, owner decision 2026-10-24)", "PROMPT.md S12"]
owner_agent: devops-engineer
risk_tier: low
---

# STORY-145 — CI on every PR and branch protection on main

## Goal
Every change reaches `main` only through a PR whose CI run of the existing make targets is green. This is the owner-approved cut of the delivery-gates addendum (2026-10-24): per-metric checks, PR-body digest, revert-on-red, release/hotfix profiles and `delivery-gate.py` are deferred until there is a real product to protect. The full 18-REQ spec and design (ADR-0005, ADR-0006, THREAT_MODEL B10) remain in git history at commit 56c4a4f for that later story.

## Requirements and acceptance criteria
### REQ-OPS-150 — The PR workflow runs the existing make targets and is green on a real PR
**Given** a PR to `main` from a story branch **When** `.github/workflows/pr.yml` runs **Then** its jobs call only existing make targets (`check-fast` per present module, `hooks-test`, `trace`, `contract-lint`, `contract-diff BASE=origin/main`, `test-integrity BASE=origin/<base>`, `eval-fast`, the gitleaks/semgrep/osv steps) and the aggregate job `gate` concludes `success`; module jobs for absent modules are skipped by the existing `detect` job.
**Given** a deliberately failing check (e.g. a failing hook test on a throwaway branch) **When** the PR runs **Then** `gate` concludes `failure`.
Unhappy path: **Given** `ANTHROPIC_API_KEY` is not a repository secret (OWNER-012) **When** the PR runs **Then** the Claude review job is skipped and `gate` does not depend on it.
Unhappy path: **Given** Dependabot opens a PR **When** CI runs **Then** it uses the same workflow; a red Dependabot PR is closed or fixed by a story, never merged red.
Evidence: a link to a green `gate` run on the PR that carries this story, plus the failing run from the throwaway branch.

REQ-OPS-151 (branch protection on main) moved to STORY-147 (deferred, owner decision 2026-10-25): GitHub refuses branch protection and rulesets on a private repo without GitHub Pro (HTTP 403, OWNER-014).

## UX states (UI stories only)
N/A

## Contract impact
None

## Data / migration impact
None

## Security / privacy / LLM-safety impact
Repository settings (branch protection) and CI permissions. Low risk tier: one combined verify+review agent.

## Eval impact
None

## Design notes (architect)
Reuse the existing `pr.yml`/`main.yml`; change only what keeps them from going green on the current tree. No new tooling.

## Dependencies
STORY-001 (DONE with accepted risk).

## Deferred (owner decision 2026-10-24)
Everything else in docs/PROMPT_DELIVERY_GATES.md: M1-M18 as separate checks, `delivery-gates.yaml`, `delivery-gate.py`, generated PR body and `pr-body-lint`, AI-review approval, revert-on-red, release/hotfix/dependabot profiles, pre-push M1/M5, selftest rows D1-D5. Reserved REQ ids REQ-OPS-152..167 are retired with this cut; the later story will allocate fresh ids.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-24 | 1-2 spec | product-analyst | READY (18 REQs, 2 spec-review loops; full version at 56c4a4f) |
| 2026-10-24 | 3 design | architect | DESIGN_READY (ADR-0005, ADR-0006) |
| 2026-10-24 | 1 spec | orchestrator | cut to REQ-OPS-150/151 per owner decision (lighter, risk-tiered process) |
| 2026-10-25 | integrate | orchestrator | DONE: REQ-OPS-150 proven on PR #2 (run 40092342642 red gate on a failing job, 40093004627 green) and every PR since; guarded by scripts/quality/tests/test_pr_gate.py; REQ-OPS-151 moved to STORY-147 |
