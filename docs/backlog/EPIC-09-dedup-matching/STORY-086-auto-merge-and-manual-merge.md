---
id: STORY-086
epic: EPIC-09
title: Auto-merge above the high threshold (feature-flagged) and manual merge endpoint
phase: 6
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-085]
reqs: [REQ-DEDUP-001, REQ-DEDUP-030, REQ-DEDUP-031]
implements: [PROMPT.md §7 (merge automatically), PROMPT.md S12 (feature flag auto-merge)]
owner_agent: backend-engineer
---

# STORY-086 — Auto-merge above the high threshold (feature-flagged) and manual merge endpoint

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
A bank SMS and the receipt photo of the same purchase become one entry (PROMPT §1 core promise, §7).

## Requirements and acceptance criteria
### REQ-DEDUP-001 — POST /api/expenses/{id}/merge merges given expenses into one with linked expense_source rows; undoable via audit
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-DEDUP-030 — Merge semantics: receipt contributes items/merchant detail; SMS contributes authoritative amount, card, post time
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-DEDUP-031 — Score ≥ high threshold → automatic merge behind flag `dedup.autoMerge`; emits EXPENSE_UPDATED
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`POST /api/expenses/{expenseId}/merge`, `MergeRequest` in v0.

## Data / migration impact
expense_source roles; merged-into pointer on IGNORED duplicates.

## Security / privacy / LLM-safety impact
Merging is destructive-ish: audit + unmerge path required.

## Eval impact
Suite `dedup`: SMS-then-receipt, receipt-then-SMS.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-085

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
