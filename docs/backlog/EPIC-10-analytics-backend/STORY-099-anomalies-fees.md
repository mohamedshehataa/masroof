---
id: STORY-099
epic: EPIC-10
title: "Anomalies: unusually large transactions, new merchant with large spend, duplicate-looking charges, fees summary"
phase: 7
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-094, STORY-096, STORY-085]
reqs: [REQ-AN-010]
implements: [PROMPT.md §10 item 10]
owner_agent: backend-engineer
---

# STORY-099 — Anomalies: unusually large transactions, new merchant with large spend, duplicate-looking charges, fees summary

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Surprises are surfaced (PROMPT §10 item 10).

## Requirements and acceptance criteria
### REQ-AN-010 — GET /api/analytics/insights anomalies via z-score/IQR per category, new-merchant alerts, duplicate-looking charges, fees & bank charges summary
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/analytics/insights`, `Insights`, `Insight` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-094, STORY-096, STORY-085

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
