---
id: STORY-064
epic: EPIC-07
title: SMS BroadcastReceiver hands off to WorkManager (never parses in the receiver)
phase: 4
status: DESCOPED
size: S
modules: [android]
depends_on: [STORY-038]
reqs: [REQ-SMS-020, REQ-SMS-021]
implements: [PROMPT.md §4.1 (BroadcastReceiver → WorkManager), PROMPT.md §11 Android-specific (battery)]
owner_agent: android-engineer
---

# STORY-064 — SMS BroadcastReceiver hands off to WorkManager (never parses in the receiver)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Every new bank SMS reaches the app's pipeline reliably without draining the battery (PROMPT §4.1).

## Requirements and acceptance criteria
### REQ-SMS-020 — Incoming SMS from allowlisted senders is enqueued as a WorkManager job within the receiver's time budget
Given an SMS from an allowlisted sender, when it arrives, then the receiver hands it to WorkManager.

### REQ-SMS-021 — Receiver does no parsing/network; StrictMode and unit tests assert it
Given any SMS, when the receiver runs, then it only does a sender lookup, a keyword check and an enqueue: no parsing, database or network.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
Room `sms_inbox` staging table.

## Security / privacy / LLM-safety impact
Only allowlisted senders are read (REQ-SMS-040).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-038

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
