---
id: STORY-102
epic: EPIC-11
title: "Budget alerts at 50/80/100% via WhatsApp and push; rollover"
phase: 7
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-092, STORY-077, STORY-071]
reqs: [REQ-AN-050, REQ-AN-051]
implements: [PROMPT.md §10 item 8]
owner_agent: backend-engineer
---

# STORY-102 — Budget alerts at 50/80/100% via WhatsApp and push; rollover

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Members are warned before they overspend (PROMPT §10 item 8).

## Requirements and acceptance criteria
### REQ-AN-050 — Crossing 50/80/100% sends one WhatsApp message and one push per threshold per period; BUDGET_ALERT stream event
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-051 — Rollover carries unspent/overspent amounts into the next period when enabled
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`StreamEvent.BUDGET_ALERT` in v0.

## Data / migration impact
`budget_alert_sent` table.

## Security / privacy / LLM-safety impact
Outbound messages contain only aggregates.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-092, STORY-077, STORY-071

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 7 start |
