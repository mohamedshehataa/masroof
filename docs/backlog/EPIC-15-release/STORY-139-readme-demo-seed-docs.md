---
id: STORY-139
epic: EPIC-15
title: "README quick start, demo seed data toggle, SETUP_ANDROID.md, DEPLOY.md (+ DEPLOY_AWS.md)"
phase: 10
status: DESCOPED
size: M
modules: [infra]
depends_on: [STORY-137, STORY-138]
reqs: [REQ-OPS-140, REQ-OPS-141, REQ-OPS-121]
implements: [PROMPT.md §15 (Deliverables), PROMPT.md §14 Phase 10]
owner_agent: docs-writer
---

# STORY-139 — README quick start, demo seed data toggle, SETUP_ANDROID.md, DEPLOY.md (+ DEPLOY_AWS.md)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 10. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
A new operator can run and install everything from the docs (PROMPT §15).

## Requirements and acceptance criteria
### REQ-OPS-140 — README.md quick start: clone → .env → make up → login with demo data, in < 10 minutes, verified on a clean machine
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-141 — Demo seed toggle (`DEMO_SEED`) documented and off in prod; `make up` seeded by default
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-121 — docs/SETUP_ANDROID.md (install the APK), docs/DEPLOY.md (VPS), docs/DEPLOY_AWS.md (optional notes), `.env.example` every variable commented
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
No secrets in docs.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-137, STORY-138

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
| 2026-10-25 | phase check | product-analyst | stays DRAFT, phase 10: PROMPT §14 item 10 (README quick start, demo seed toggle) is Release, not Phase 1 Foundation |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
