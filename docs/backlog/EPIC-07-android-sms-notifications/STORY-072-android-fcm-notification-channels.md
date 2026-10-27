---
id: STORY-072
epic: EPIC-07
title: "Android FCM registration, notification channels per type and tap-to-recategorize"
phase: 4
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-071, STORY-037]
reqs: [REQ-AND-034, REQ-AND-035]
implements: [PROMPT.md §11 Android-specific (notifications channel per type), PROMPT.md §6 step 7]
owner_agent: android-engineer
---

# STORY-072 — Android FCM registration, notification channels per type and tap-to-recategorize

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Notifications land in the right channel and open the right screen (PROMPT §11).

## Requirements and acceptance criteria
### REQ-AND-034 — FCM token registered/refreshed with /api/devices; channels: new auto-entry, review needed, budget alert, monthly report
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-035 — Tapping a notification deep-links to the Transaction detail / Review inbox; quick action 'Recategorize'
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A screens · RTL notification text · dark

## Contract impact
Uses `/api/devices`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
POST_NOTIFICATIONS permission rationale.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-071, STORY-037

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
