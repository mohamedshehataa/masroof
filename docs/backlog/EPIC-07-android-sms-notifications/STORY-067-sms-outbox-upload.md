---
id: STORY-067
epic: EPIC-07
title: "SMS outbox upload with backoff and idempotency on (device_id, sms_id)"
phase: 4
status: IN_PROGRESS
size: S
modules: [android]
depends_on: [STORY-038, STORY-056, STORY-054]
reqs: [REQ-SMS-060, REQ-SMS-061, REQ-SMS-062, REQ-SMS-063]
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
