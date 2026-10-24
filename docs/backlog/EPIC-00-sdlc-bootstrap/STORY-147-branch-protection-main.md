---
id: STORY-147
epic: EPIC-00
title: "Branch protection on main (deferred: needs GitHub Pro or a public repo)"
phase: 0
status: DESCOPED
size: XS
modules: [sdlc]
depends_on: [STORY-145]
reqs: [REQ-OPS-151]
implements: ["PROMPT_DELIVERY_GATES.md §0 (cut down)", "PROMPT.md S12"]
risk_tier: low
owner_agent: devops-engineer
---

# STORY-147 — Branch protection on main (deferred)

## Goal
Protect `main` once GitHub allows it for this repository. Owner decision 2026-10-25 ("do whatever asks me the least"): stay unprotected for now (OWNER-014 option c). CI runs on every PR, the local guard-bash hook blocks pushes and force-pushes to main, and only checked PRs are squash-merged. Pick this up if the account moves to GitHub Pro or the repo becomes public; then run `bash scripts/sdlc/apply-branch-protection.sh`.

## Requirements and acceptance criteria
### REQ-OPS-151 — main is protected
**Given** the repository `mohamedshehataa/masroof` **When** branch protection is applied to `main` (via `gh api` with the owner's token, recorded as a script `scripts/sdlc/apply-branch-protection.sh` so it is reviewable and re-runnable) **Then** `gh api repos/mohamedshehataa/masroof/branches/main/protection` shows: required status check `gate` (strict, up to date with main), linear history required, force pushes disabled, deletions disabled, pull request required before merging with **0** required approvals (the owner merges personally; GitHub forbids author approval, OWNER-011), and `enforce_admins` false so the owner can still merge.
**Given** a direct push to `main` **When** it is attempted **Then** GitHub rejects it.
Unhappy path: **Given** the protection call fails (scope or plan limits) **Then** the script exits non-zero with GitHub's message and the story stays IN_PROGRESS.

## Status log
| date | stage | agent | note |
|---|---|---|---|
| 2026-10-25 | spec | orchestrator | split out of STORY-145; the protection call returned HTTP 403 (needs GitHub Pro on a private repo) |
| 2026-10-25 | descope | product-analyst | owner decision 2026-10-25: stay unprotected (OWNER-014 option c); revive if the account moves to GitHub Pro or the repo becomes public |
