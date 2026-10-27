---
id: STORY-060
epic: EPIC-06
title: "Web Add sheet: Paste text and Scan/upload receipt"
phase: 3
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-028, STORY-055]
reqs: [REQ-UI-031, REQ-UI-032]
implements: ["PROMPT.md §11 Navigation 3 (Scan receipt, Paste text)", PROMPT.md §1 item 5]
owner_agent: web-engineer
---

# STORY-060 — Web Add sheet: Paste text and Scan/upload receipt

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The PWA can ingest any bank message or receipt, not just typed amounts (PROMPT §11 Add).

## Requirements and acceptance criteria
### REQ-UI-031 — Paste text: textarea → dry-run preview → confirm → POST /api/ingest/text
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-UI-032 — Scan receipt: camera/file input (image/PDF), client-side downscale, upload progress → POST /api/ingest/receipt; result card
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading (upload %) · error (too large, unsupported) · offline (disabled) · RTL · dark

## Contract impact
Uses `/api/parse/dry-run`, `/api/ingest/text`, `/api/ingest/receipt`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Files are sent only to our backend; EXIF stripped client-side.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-028, STORY-055

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
