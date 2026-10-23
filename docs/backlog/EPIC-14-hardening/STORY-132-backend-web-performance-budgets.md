---
id: STORY-132
epic: EPIC-14
title: "Backend and web performance budgets: k6 p95, Lighthouse, size-limit"
phase: 9
status: DRAFT
size: M
modules: [backend, web, infra]
depends_on: [STORY-101, STORY-130]
reqs: [REQ-OPS-110, REQ-OPS-111]
implements: [PROMPT.md S9 (Non-functional), PROMPT.md §14 Phase 9]
owner_agent: performance-engineer
---

# STORY-132 — Backend and web performance budgets: k6 p95, Lighthouse, size-limit

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Budgets are met and enforced nightly (PROMPT S9).

## Requirements and acceptance criteria
### REQ-OPS-110 — k6: reads p95 < 200 ms, writes < 400 ms at 20 rps; webhook ack < 1 s p99; ingestion budgets measured in `make perf`
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-111 — Lighthouse mobile Performance ≥ 90, Best Practices ≥ 95; initial JS ≤ 200 KB gzip (size-limit)
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
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-101, STORY-130

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
