---
id: STORY-091
epic: EPIC-09
title: Dedup fixture scenarios as integration tests and the dedup eval suite
phase: 6
status: DESCOPED
size: M
modules: [backend, eval]
depends_on: [STORY-086, STORY-087, STORY-090]
reqs: [REQ-DEDUP-060, REQ-DEDUP-061]
implements: [PROMPT.md §7 (Unit-test with fixture scenarios), "PROMPT.md §13 (Dedup: every scenario is an integration test and an eval case)", PROMPT.md S10.2]
owner_agent: ai-eval-engineer
---

# STORY-091 — Dedup fixture scenarios as integration tests and the dedup eval suite

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Phase 6 exit is measurable (PROMPT §13).

## Requirements and acceptance criteria
### REQ-DEDUP-060 — Scenarios SMS-then-receipt, receipt-then-SMS, two identical coffees same day (kept separate), foreign-currency charge vs EGP receipt, refund matching — each an integration test and a dedup eval case
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-DEDUP-061 — Dedup eval gates: pairwise precision ≥ 99%, recall ≥ 95%; suite 20 → 60
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
This story grows the `dedup` suite and baseline.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-086, STORY-087, STORY-090

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 6 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
