---
id: STORY-049
epic: EPIC-05
title: Persist entries as CONFIRMED or PENDING_REVIEW by confidence and conflicts; calibration
phase: 3
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-045, STORY-012]
reqs: [REQ-PARSE-090, REQ-PARSE-091, REQ-PARSE-092]
implements: [PROMPT.md §6 step 6, PROMPT.md S10.2 (calibration)]
owner_agent: backend-engineer
---

# STORY-049 — Persist entries as CONFIRMED or PENDING_REVIEW by confidence and conflicts; calibration

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Only entries the system is sure about are auto-confirmed; everything else waits in the review inbox (PROMPT §6 step 6).

## Requirements and acceptance criteria
### REQ-PARSE-090 — confidence ≥ autoConfirmThreshold and no conflicts → CONFIRMED else PENDING_REVIEW
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-091 — autoConfirmThreshold from settings (default 0.85) applies at persist time
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-092 — Calibration: precision of auto-CONFIRMED ≥ 99% in eval; threshold tuned and recorded when not met
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`ExpenseStatus` in v0; `Settings.autoConfirmThreshold`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None beyond REQ-SEC-022 bounds (reason: policy over already-bounded output).

## Eval impact
Calibration metric; regression tolerance 1 pp.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-045, STORY-012

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
