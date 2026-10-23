---
id: STORY-116
epic: EPIC-13
title: PWA Web Share Target for images/PDFs/text
phase: 8
status: DRAFT
size: S
modules: [web]
depends_on: [STORY-113, STORY-060]
reqs: [REQ-PWA-040]
implements: [PROMPT.md §5 item 4 (Web Share Target), PROMPT.md §11 PWA-specific]
owner_agent: web-engineer
---

# STORY-116 — PWA Web Share Target for images/PDFs/text

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 8. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Share a screenshot straight into the tracker where supported (PROMPT §5).

## Requirements and acceptance criteria
### REQ-PWA-040 — manifest share_target (POST multipart) receives images/PDF/text and opens the Add flow pre-filled
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
loading · error (unsupported browser) · RTL · dark

## Contract impact
Uses `/api/ingest/receipt`, `/api/ingest/text`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Shared files handled client-side then uploaded to our backend only.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-113, STORY-060

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 8 start |
