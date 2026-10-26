---
id: STORY-067
epic: EPIC-07
title: "SMS outbox upload with backoff and idempotency on (device_id, sms_id)"
phase: 4
status: IN_PROGRESS
size: S
modules: [android]
depends_on: [STORY-038, STORY-056, STORY-054]
reqs: [REQ-SMS-060, REQ-SMS-061, REQ-SMS-062, REQ-SMS-063, REQ-SMS-090, REQ-SMS-091, REQ-SMS-092, REQ-SMS-093]
implements: ["PROMPT.md §4.1 (Upload… idempotent; offline → Room outbox, retry with backoff)"]
owner_agent: android-engineer
---

# STORY-067 — SMS outbox upload with backoff and idempotency on (device_id, sms_id)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Each bank SMS is uploaded exactly once, even across reboots and offline periods (PROMPT §4.1).

## Requirements and acceptance criteria
### REQ-SMS-060 — Parsed records are posted to /api/ingest/parsed-transactions with the device id; 202 removes them from the outbox
Given a parsed record in the outbox, when uploaded, then it is posted to /api/ingest/parsed-transactions with the device id and a 202 removes it.

### REQ-SMS-061 — Offline/5xx → retry with exponential backoff; bodies deleted locally after acknowledgement (retention ≤ 7 days)
Given the server is offline or answers 5xx, when uploading, then the row stays and is retried with exponential backoff; given a permanent rejection, then it is marked failed; failed bodies are purged after 7 days.


### REQ-SMS-062 — No SMS or notification text ever leaves the phone
Given any message, secret or not, when the app uploads, then the serialized request holds only typed fields and a digit-free merchant label; a code, PIN or reference number never appears in it, and the app never calls /api/ingest/sms or /api/ingest/notification.

### REQ-SMS-063 — Messages not turned into a record stay in a local "Needs review" list
Given an unknown template, a foreign currency, a secret-looking purchase or an unexplained number, when it arrives, then it is kept only in Room (digits of a secret one masked) where the user can open it, create a prefilled expense, or dismiss it; it is purged after 30 days and cleared on logout.

### REQ-SMS-090 — One transfer announced by two channels is one record
Given a bank SMS and an InstaPay app notification for the same transfer (same direction, amount and currency, at most 5 minutes apart, in either order), when both are captured, then one record is uploaded and one notice is shown: when the notification came first, the richer bank SMS replaces it and the first notice is withdrawn; when the first record was already uploaded, the second is dropped. Given two equal transfers more than 5 minutes apart, a different amount, or two equal messages from the same channel, then each is its own record. Each record pairs with at most one record of the other channel, so the result does not depend on the order messages arrive in; the channels are paired on the time the phone received each message; a queued record whose upload has started is never replaced (the second message is the duplicate), so the server holds exactly one record per transfer.

### REQ-SMS-091 — The capture notice says what happened
Given a captured record, when its notice is shown, then the wording follows the direction: money in reads "Received", money out reads "Sent", and a card purchase reads "Recorded", each with the amount and the merchant or source label.

### REQ-SMS-092 — Totals count only spending
Given received money (income), transfers and expenses in the local list, when the spending total is computed, then only expenses are added; income and transfers are listed but never counted.

### REQ-SMS-093 — List rows say what kind of money movement they are
Given list rows, when they are shown, then the label follows the type and not the review state alone: income reads as money received, a transfer reads as a transfer, and an expense held for review reads as needing review (never as received money).

## UX states (UI stories only)
N/A (no UI).

## Contract impact
Uses `/api/ingest/sms`, device token from `/api/devices`.

## Data / migration impact
Outbox kind UPLOAD_SMS.

## Security / privacy / LLM-safety impact
Device token in encrypted storage; bodies encrypted at rest in Room (SQLCipher or app-level) — architect decides.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-038, STORY-056, STORY-054

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 4 start |
