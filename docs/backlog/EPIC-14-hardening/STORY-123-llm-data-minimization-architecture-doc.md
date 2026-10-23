---
id: STORY-123
epic: EPIC-14
title: LLM data minimization and ARCHITECTURE.md data-flow/privacy documentation
phase: 9
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-045, STORY-053]
reqs: [REQ-SEC-044, REQ-OPS-120]
implements: [PROMPT.md §12 (Only send to the LLM what parsing needs), PROMPT.md §15 (ARCHITECTURE.md)]
owner_agent: ai-parsing-engineer
---

# STORY-123 — LLM data minimization and ARCHITECTURE.md data-flow/privacy documentation

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The model sees the minimum (PROMPT §12).

## Requirements and acceptance criteria
### REQ-SEC-044 — Only parsing-relevant fields are sent to the LLM; balances/account numbers stripped where not required; verified by prompt-content tests
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-120 — docs/ARCHITECTURE.md with Mermaid diagrams, data flows and privacy notes
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
LLM privacy control.

## Eval impact
Adversarial/regression suites unchanged.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-045, STORY-053

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 9 start |
