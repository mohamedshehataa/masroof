---
id: STORY-056
epic: EPIC-06
title: "Ingest endpoints for SMS and push-notification texts (device token, idempotent)"
phase: 3
status: IN_PROGRESS
size: S
modules: [backend]
depends_on: [STORY-055, STORY-044]
reqs: [REQ-SMS-001, REQ-NTF-001]
implements: [PROMPT.md §4.1 (POST /api/ingest/sms), PROMPT.md §4.2, PROMPT.md §5 item 3]
owner_agent: backend-engineer
---

# STORY-056 — Ingest endpoints for SMS and push-notification texts (device token, idempotent)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Bank SMS and bank-app notifications from any device enter the same pipeline exactly once (PROMPT §4).

## Requirements and acceptance criteria
### REQ-SMS-001 — POST /api/ingest/sms {sender, body, receivedAt, deviceId, smsId} → 202; idempotent on (deviceId, smsId); source SMS or IOS_SHORTCUT by device platform
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-NTF-001 — POST /api/ingest/notification {packageName, title, text, postedAt, deviceId, notificationKey} → 202; source PUSH_NOTIFICATION; idempotent
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/ingest/sms`, `/api/ingest/notification`, `IngestSmsRequest`, `IngestNotificationRequest` in v0.

## Data / migration impact
None beyond V4.

## Security / privacy / LLM-safety impact
Server re-checks OTP/promo classification (defence in depth); bodies never logged.

## Eval impact
Suites `sms`, `notifications`.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-055, STORY-044

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate S; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
