---
id: STORY-069
epic: EPIC-07
title: Bank-app push notification listener for allowlisted packages
phase: 4
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-065, STORY-067]
reqs: [REQ-NTF-010, REQ-NTF-011, REQ-NTF-012, REQ-NTF-013, REQ-NTF-014]
implements: [PROMPT.md §4.2, PROMPT.md §11 Onboarding (optional notification access)]
owner_agent: android-engineer
---

# STORY-069 — Bank-app push notification listener for allowlisted packages

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Transactions that only arrive as app notifications (InstaPay) are captured too (PROMPT §4.2).

## Requirements and acceptance criteria
### REQ-NTF-010 — Optional NotificationListenerService; only allowlisted packages (InstaPay, bank apps, wallets) are read
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-NTF-011 — Title + text extracted and normalized; grouped/summary notifications handled
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-NTF-012 — Same OTP/promo filtering as SMS
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-NTF-013 — Parsed on the device and uploaded as a structured record with source NOTIFICATION via the outbox
Given an allowlisted notification, when it is posted, then its title and text are parsed on the device and only the structured record is uploaded to /api/ingest/parsed-transactions (stored with source PUSH); the text is never uploaded.


### REQ-NTF-014 — Notification capture is opt-in, explained in Arabic and English, and the app works without it
Given Settings, when the user turns notification capture on, then a clear explanation is shown and the system Notification access screen opens; given the access is not granted or the toggle is off, then nothing is read and the app is fully usable. The monitored packages default to the InstaPay candidate package and can be edited.

## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/ingest/notification`.

## Data / migration impact
Room `package_allowlist`.

## Security / privacy / LLM-safety impact
Notification access is a sensitive permission: clear rationale, allowlist-only, revocable in Sources.

## Eval impact
Suite `notifications` 20 cases.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-065, STORY-067

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
