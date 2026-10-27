---
id: STORY-061
epic: EPIC-06
title: "Android Add sheet: Paste text and ML Kit document scanner receipt upload"
phase: 3
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-036, STORY-055]
reqs: [REQ-AND-021, REQ-AND-022]
implements: [PROMPT.md §11 Navigation 3 (ML Kit document scanner), PROMPT.md §2 (CameraX + ML Kit)]
owner_agent: android-engineer
---

# STORY-061 — Android Add sheet: Paste text and ML Kit document scanner receipt upload

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Receipts are scanned natively with edge detection and uploaded even when offline (PROMPT §11).

## Requirements and acceptance criteria
### REQ-AND-021 — Paste text: text field (prefilled from clipboard with consent) → dry-run preview → ingest
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-022 — Scan receipt with ML Kit document scanner (multi-page), compressed upload via outbox → POST /api/ingest/receipt
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline (queued) · RTL · dark

## Contract impact
Uses `/api/parse/dry-run`, `/api/ingest/text`, `/api/ingest/receipt`.

## Data / migration impact
Outbox kind UPLOAD_RECEIPT with file reference.

## Security / privacy / LLM-safety impact
Camera permission rationale; images stored app-private until uploaded then deleted.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-036, STORY-055

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
