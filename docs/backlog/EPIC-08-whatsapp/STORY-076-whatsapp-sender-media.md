---
id: STORY-076
epic: EPIC-08
title: "WhatsApp sender → member mapping, unknown senders, media download to object storage"
phase: 5
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-075, STORY-053]
reqs: [REQ-WA-010, REQ-WA-011, REQ-WA-030, REQ-WA-031]
implements: ["PROMPT.md §3.1 (media, sender mapping, unknown numbers)"]
owner_agent: backend-engineer
---

# STORY-076 — WhatsApp sender → member mapping, unknown senders, media download to object storage

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Only the two members' messages are processed, and their photos/PDFs/voice notes are safely stored (PROMPT §3.1).

## Requirements and acceptance criteria
### REQ-WA-010 — Sender phone (wa_id) mapped to member; group vs direct context recorded
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-011 — Messages from unknown numbers are stored, ignored and logged (count metric), never parsed
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-030 — Image/document/audio media downloaded via the Media API within the URL validity window and stored (media row)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-031 — Download failures retried; permanent failure → raw_message error, reply asks to resend
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
member.whatsapp_id used; media table.

## Security / privacy / LLM-safety impact
Unknown-sender content is never sent to the LLM; media stored with server-side encryption (Phase 9).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-075, STORY-053

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 5 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
