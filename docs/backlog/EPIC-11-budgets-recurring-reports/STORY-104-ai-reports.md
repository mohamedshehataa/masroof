---
id: STORY-104
epic: EPIC-11
title: AI monthly narrative report and weekly mini-summary with deterministic number check and LLM-as-judge
phase: 7
status: DRAFT
size: M
modules: [backend, eval]
depends_on: [STORY-093, STORY-094, STORY-099, STORY-077]
reqs: [REQ-AN-012, REQ-AN-070, REQ-AN-071]
implements: [PROMPT.md §10 item 12, PROMPT.md S10.3]
owner_agent: ai-parsing-engineer
---

# STORY-104 — AI monthly narrative report and weekly mini-summary with deterministic number check and LLM-as-judge

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The household gets a trustworthy monthly story (PROMPT §10 item 12).

## Requirements and acceptance criteria
### REQ-AN-012 — On month start day: short bilingual narrative (what changed, biggest drivers, 3 suggestions) posted to WhatsApp and saved; weekly mini-summary on the configured day
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-070 — Every number in the report matches the analytics API (deterministic check fails the run)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AN-071 — LLM-as-judge rubric (different model) ≥ 4/5 average, no item < 3
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`Insights.latestNarrative` or new `GET /api/analytics/reports` (architect decides).

## Data / migration impact
`report` table.

## Security / privacy / LLM-safety impact
Report generation sends only aggregates to the LLM.

## Eval impact
Suite `narrative` 6 → 12 months; S10.3 gates.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-093, STORY-094, STORY-099, STORY-077

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
