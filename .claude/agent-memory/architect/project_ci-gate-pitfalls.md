---
name: ci-gate-pitfalls
description: GitHub Actions facts that shaped the STORY-145 delivery-gate CI design (skipped = success, head-defined pull_request workflows, GITHUB_TOKEN events, Windows git cost)
metadata:
  type: project
---
These facts drove the STORY-145 / ADR-0005 CI design (2026-10-24):
- GitHub counts a **skipped** required check as passing, so `gate/*` jobs never `if:`-skip on `pull_request` runs. Push-event jobs are named `push gate/…`.
- `pull_request` runs use the PR's **own** workflow file, so a PR can fake its checks or the Action approval. This is an accepted risk (THREAT_MODEL B10); the follow-up is a `workflow_run` approval from main.
- PRs and pushes made with `GITHUB_TOKEN` trigger no workflows, which is why revert-on-red needs `GATE_BOT_TOKEN` (OWNER-013) and why release-please left the route.
- A temp-repo git call costs about 40–80 ms on Windows, so gate tests are pure-`judge()` first, with few real-repo fixtures (check-fast < 180 s).

**Why:** each fact either breaks a gate silently or blows the check-fast budget.
**How to apply:** re-check these facts before designing any workflow or gate change. See [[dependency-verification]].
