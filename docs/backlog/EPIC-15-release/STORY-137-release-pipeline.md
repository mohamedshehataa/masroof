---
id: STORY-137
epic: EPIC-15
title: "Release pipeline: staging → production via /release with smoke tests, auto-rollback, SBOM and signed images"
phase: 10
status: DRAFT
size: M
modules: [infra]
depends_on: [STORY-016, STORY-124, STORY-133]
reqs: [REQ-OPS-130, REQ-OPS-131, REQ-OPS-133]
implements: [PROMPT.md S12 (release.yml), PROMPT.md S2 stage 9, PROMPT.md §14 Phase 10]
owner_agent: devops-engineer
---

# STORY-137 — Release pipeline: staging → production via /release with smoke tests, auto-rollback, SBOM and signed images

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 10. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Releases are boring (PROMPT S12). Human checkpoint: first production deploy (S16).

## Requirements and acceptance criteria
### REQ-OPS-130 — `make deploy-staging` / `deploy-prod` / `smoke` / `rollback` work from release.yml on tag v*; staging smoke green → prod deploy → post-deploy smoke
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-131 — Health or smoke failure after deploy triggers automatic rollback to previous images; DB backup taken before deploy
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-133 — CycloneDX SBOMs for backend, web, Android attached to the GitHub Release; images optionally cosign-signed
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Secrets in CI only; `TODO(owner)` server/domain.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-016, STORY-124, STORY-133

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 10 start |
