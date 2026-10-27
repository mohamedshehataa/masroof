---
id: STORY-083
epic: EPIC-08
title: "Android onboarding and Sources: link WhatsApp (group invite link / QR, link status)"
phase: 5
status: DESCOPED
size: S
modules: [android]
depends_on: [STORY-070, STORY-073, STORY-078]
reqs: [REQ-AND-037]
implements: [PROMPT.md §11 Onboarding (link WhatsApp), PROMPT.md §11 Navigation 5 (WhatsApp link status)]
owner_agent: android-engineer
---

# STORY-083 — Android onboarding and Sources: link WhatsApp (group invite link / QR, link status)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Mohamed joins the bot's group from the app (PROMPT §11).

## Requirements and acceptance criteria
### REQ-AND-037 — Onboarding step and Sources row show the group invite link/QR and link status from settings; opens WhatsApp
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty (not configured) · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/settings` additions from STORY-077.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (link is shareable by design).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-070, STORY-073, STORY-078

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 5 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
