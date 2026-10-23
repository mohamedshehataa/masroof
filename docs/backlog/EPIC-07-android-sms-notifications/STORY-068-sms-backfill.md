---
id: STORY-068
epic: EPIC-07
title: "Historical SMS backfill with date range (default 90 days), progress and cancel"
phase: 4
status: DRAFT
size: M
modules: [android]
depends_on: [STORY-065, STORY-066, STORY-067]
reqs: [REQ-SMS-050, REQ-SMS-051, REQ-SMS-052]
implements: [PROMPT.md §4.1 (Backfill), PROMPT.md §11 Onboarding (backfill range)]
owner_agent: android-engineer
---

# STORY-068 — Historical SMS backfill with date range (default 90 days), progress and cancel

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Past months of bank SMS become entries on first setup (PROMPT §4.1).

## Requirements and acceptance criteria
### REQ-SMS-050 — User picks a date range (default last 90 days) during onboarding or from Sources
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-051 — Telephony.Sms.Inbox is read via ContentResolver for allowlisted senders only, filtered (OTP/promo) and queued to the outbox in batches
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-052 — Progress UI, cancel, and resume without duplicates (idempotent on sms_id)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading (progress) · error · offline (queued) · RTL · dark

## Contract impact
Uses `/api/ingest/sms`.

## Data / migration impact
Room outbox batches.

## Security / privacy / LLM-safety impact
Same filters as live SMS; backfill rate-limited to respect REQ-SEC-030.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-065, STORY-066, STORY-067

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
