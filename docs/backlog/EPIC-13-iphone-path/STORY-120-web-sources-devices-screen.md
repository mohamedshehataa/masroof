---
id: STORY-120
epic: EPIC-13
title: "Web More → Sources: WhatsApp link status, devices and tokens"
phase: 8
status: DESCOPED
size: S
modules: [web]
depends_on: [STORY-031, STORY-054, STORY-078]
reqs: [REQ-UI-035]
implements: [PROMPT.md §11 Navigation 5 (Sources)]
owner_agent: web-engineer
---

# STORY-120 — Web More → Sources: WhatsApp link status, devices and tokens

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 8. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2 controls their devices and tokens (PROMPT §11 More).

## Requirements and acceptance criteria
### REQ-UI-035 — Sources screen lists devices (platform, name, last seen, revoke), WhatsApp link status and invite link, push subscription state
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/devices*`, `/api/settings`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Revocation immediate.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-031, STORY-054, STORY-078

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
