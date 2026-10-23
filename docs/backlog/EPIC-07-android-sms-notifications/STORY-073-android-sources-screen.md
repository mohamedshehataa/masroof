---
id: STORY-073
epic: EPIC-07
title: "Android More → Sources: SMS senders, notification apps, devices"
phase: 4
status: DRAFT
size: M
modules: [android]
depends_on: [STORY-066, STORY-069, STORY-054]
reqs: [REQ-AND-036]
implements: [PROMPT.md §11 Navigation 5 (Sources), PROMPT.md §4.1 (Settings → Sources)]
owner_agent: android-engineer
---

# STORY-073 — Android More → Sources: SMS senders, notification apps, devices

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
All ingestion sources are visible and controllable in one screen (PROMPT §11 More).

## Requirements and acceptance criteria
### REQ-AND-036 — Sources screen manages sender allowlist + suggestions, notification package allowlist, this device's token/last seen and other devices (revoke, admin)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/devices*`.

## Data / migration impact
Room allowlists.

## Security / privacy / LLM-safety impact
Revocation is immediate (REQ-SEC-004).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-066, STORY-069, STORY-054

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 4 start |
