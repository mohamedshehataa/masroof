---
id: STORY-140
epic: EPIC-15
title: Release-level eval dataset sizes and thresholds met; baseline committed
phase: 10
status: DRAFT
size: M
modules: [eval]
depends_on: [STORY-050, STORY-091, STORY-104]
reqs: [REQ-PARSE-097]
implements: [PROMPT.md S10.1, PROMPT.md §14 Phase 10, PROMPT.md §15]
owner_agent: ai-eval-engineer
---

# STORY-140 — Release-level eval dataset sizes and thresholds met; baseline committed

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 10. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The parser is proven at release scale (PROMPT S10).

## Requirements and acceptance criteria
### REQ-PARSE-097 — Datasets reach S10.1 release sizes (sms 250, notifications 80, whatsapp_text 200, commands 80, receipts 60, voice 25, dedup 60, adversarial 60, narrative 12 months); all S10.2 gates pass; eval/baseline.json committed
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Only anonymized data.

## Eval impact
This story is the final eval gate.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-050, STORY-091, STORY-104

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 10 start |
