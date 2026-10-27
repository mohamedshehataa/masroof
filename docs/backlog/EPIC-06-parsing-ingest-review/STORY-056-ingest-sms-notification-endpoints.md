---
id: STORY-056
epic: EPIC-06
title: "Ingest endpoints for SMS and push-notification texts (device token, idempotent)"
phase: 3
status: DESCOPED
size: S
modules: [backend]
depends_on: [STORY-055, STORY-044]
reqs: [REQ-SMS-001, REQ-SMS-003, REQ-NTF-001]
implements: [PROMPT.md §4.1 (POST /api/ingest/sms), PROMPT.md §4.2, PROMPT.md §5 item 3]
owner_agent: backend-engineer
---

# STORY-056 — Ingest endpoints for SMS and push-notification texts (device token, idempotent)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Bank SMS and bank-app notifications from any device enter the same pipeline exactly once (PROMPT §4).

## Requirements and acceptance criteria
### REQ-SMS-001 — POST /api/ingest/sms {sender, body, receivedAt, deviceId, smsId} → 202; idempotent on (deviceId, smsId); source SMS or IOS_SHORTCUT by device platform
Given a valid access token, when POST /api/ingest/sms is sent twice with the same deviceId and smsId, then both answer 202 and the second says DUPLICATE with the same id; given an OTP, then REJECTED_OTP and nothing is stored; given bad input, then 4xx without echoing the text.

### REQ-SMS-003 — POST /api/ingest/parsed-transactions {deviceId, localId, source, kind, amountMinor, currency, occurredAt, instrumentLast4, senderId, institution, paymentMethod, merchant} → 202; structured only
Given the phone parsed a bank SMS or notification, when it posts the record, then the server validates strictly (enum kind, amount 1..10^13, household currency, last4 `^[0-9]{4}$` or null, sender id, digit-free secret-free merchant label of at most 40 characters), creates a CONFIRMED expense with source SMS or PUSH and answers 202; the same (deviceId, localId) answers DUPLICATE with the original id; another household is a separate record; no message text is stored anywhere (the evidence row's body is empty); a request over 16 KiB is 413 and more than 100 per minute is 429.

### REQ-NTF-001 — POST /api/ingest/notification {packageName, title, text, postedAt, deviceId, notificationKey} → 202; source PUSH_NOTIFICATION; idempotent
Given a notification, when posted, then it is queued. NOT built in the SMS slice (still 501).


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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
