---
id: STORY-118
epic: EPIC-13
title: "iOS Shortcuts: SMS forwarding and share-sheet receipt upload with per-device token; setup docs"
phase: 8
status: DESCOPED
size: M
modules: [backend, web]
depends_on: [STORY-056, STORY-054, STORY-055]
reqs: [REQ-IOS-001, REQ-IOS-010, REQ-IOS-011, REQ-IOS-020]
implements: [PROMPT.md §5 items 3-4, PROMPT.md §15 (SETUP_IOS_SHORTCUT.md)]
owner_agent: docs-writer
---

# STORY-118 — iOS Shortcuts: SMS forwarding and share-sheet receipt upload with per-device token; setup docs

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 8. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2's bank SMS reach the backend without an app (PROMPT §5).

## Requirements and acceptance criteria
### REQ-IOS-001 — POST /api/ingest/sms from an IOS_SHORTCUT device token is accepted and tagged source IOS_SHORTCUT
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-IOS-010 — docs/SETUP_IOS_SHORTCUT.md: step-by-step Personal Automation (Message contains keywords from bank senders → Get Contents of URL with token header), 'Ask Before Running' off
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-IOS-011 — PWA Sources screen creates/revokes the Shortcut device token and shows a copyable snippet
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-IOS-020 — Second Shortcut: share image/PDF → POST /api/ingest/receipt; documented
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
Sources screen states · RTL · dark

## Contract impact
Uses existing ingest endpoints.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Per-device token (REQ-SEC-004/009); OTP filtering happens server-side for this path (keywords filter in the Shortcut recommended).

## Eval impact
Suite `sms` iOS-forwarded formatting cases.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-056, STORY-054, STORY-055

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 8 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
