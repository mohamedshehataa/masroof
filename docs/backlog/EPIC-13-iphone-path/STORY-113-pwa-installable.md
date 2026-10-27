---
id: STORY-113
epic: EPIC-13
title: "PWA installability: manifest, icons/splash, iOS meta tags, safe areas, standalone; Add to Home Screen guide"
phase: 8
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-017]
reqs: [REQ-PWA-010, REQ-PWA-011]
implements: [PROMPT.md §11 PWA-specific, PROMPT.md §5 item 2, PROMPT.md S9 (PWA installable)]
owner_agent: web-engineer
---

# STORY-113 — PWA installability: manifest, icons/splash, iOS meta tags, safe areas, standalone; Add to Home Screen guide

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 8. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The PWA feels native on Member 2's iPhone (PROMPT §5).

## Requirements and acceptance criteria
### REQ-PWA-010 — Manifest with icons/splash, iOS meta tags, standalone display, safe-area insets; Lighthouse 'installable' passes
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PWA-011 — In-app 'Add to Home Screen' guide for iOS Safari and install prompt on Android/desktop
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-017

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
