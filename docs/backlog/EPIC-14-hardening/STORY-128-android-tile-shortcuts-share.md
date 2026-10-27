---
id: STORY-128
epic: EPIC-14
title: "Android Quick Settings tile, app shortcuts and share target"
phase: 9
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-036, STORY-061]
reqs: [REQ-AND-091, REQ-AND-092]
implements: ["PROMPT.md §11 Android-specific (Quick Settings tile, app shortcut, Share target)"]
owner_agent: android-engineer
---

# STORY-128 — Android Quick Settings tile, app shortcuts and share target

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Adding is one tap from anywhere (PROMPT §11).

## Requirements and acceptance criteria
### REQ-AND-091 — Quick Settings tile and static app shortcuts for Quick add / Scan receipt
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-092 — Share target: image/PDF/text from any app opens the matching Add flow
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
RTL · dark

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Exported components protected per MASVS.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-036, STORY-061

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 9 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
