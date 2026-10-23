---
id: STORY-101
epic: EPIC-10
title: "Analytics query performance: no sequential scans above 10k rows, p95 < 200 ms, no N+1"
phase: 7
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-093, STORY-094, STORY-095, STORY-096, STORY-097, STORY-098, STORY-099, STORY-100]
reqs: [REQ-AN-040]
implements: ["PROMPT.md S9 (DB, API latency)"]
owner_agent: performance-engineer
---

# STORY-101 — Analytics query performance: no sequential scans above 10k rows, p95 < 200 ms, no N+1

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 7. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Analytics stay fast as the ledger grows (PROMPT S9).

## Requirements and acceptance criteria
### REQ-AN-040 — EXPLAIN checks for every analytics query at 10k+ rows; k6 p95 < 200 ms at 20 rps; Hibernate statistics assert no N+1
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
Indexes/materialized views by new migration if needed.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-093, STORY-094, STORY-095, STORY-096, STORY-097, STORY-098, STORY-099, STORY-100

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 7 start |
