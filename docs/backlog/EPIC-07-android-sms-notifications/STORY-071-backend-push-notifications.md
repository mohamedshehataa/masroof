---
id: STORY-071
epic: EPIC-07
title: Backend push notifications (FCM) for new auto-entries and review requests
phase: 4
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-049, STORY-054]
reqs: [REQ-NTF-020]
implements: [PROMPT.md §6 step 7 (Notify), PROMPT.md §11 Android-specific (notification channel per type)]
owner_agent: backend-engineer
---

# STORY-071 — Backend push notifications (FCM) for new auto-entries and review requests

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Mohamed is told when an SMS became an entry and can tap to fix it (PROMPT §6 step 7). Web push is STORY-114.

## Requirements and acceptance criteria
### REQ-NTF-020 — After an SMS/PUSH-sourced entry is persisted, an FCM push (data message) is sent to the member's Android devices: new auto-entry or review needed; token registration via /api/devices
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`DeviceInput.pushToken` addition (non-breaking).

## Data / migration impact
`device.push_token` column (new migration).

## Security / privacy / LLM-safety impact
FCM credentials in .env (`TODO(owner)` FCM project); payload carries ids and short summary only.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-049, STORY-054

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
