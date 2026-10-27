---
id: STORY-048
epic: EPIC-05
title: Multi-item messages and low-confidence clarification questions
phase: 3
status: DESCOPED
size: S
modules: [backend]
depends_on: [STORY-045]
reqs: [REQ-PARSE-050, REQ-PARSE-051]
implements: [PROMPT.md §3.4 (two items → ask or split; low confidence → best guess + question), PROMPT.md §6 step 6]
owner_agent: ai-parsing-engineer
---

# STORY-048 — Multi-item messages and low-confidence clarification questions

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Ambiguous messages still produce an entry and one short question instead of silence or a wrong confident entry (PROMPT §3.4).

## Requirements and acceptance criteria
### REQ-PARSE-050 — `$40 Netflix + Spotify` → either two entries or one PENDING_REVIEW with a clarification question, per confidence
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-051 — Confidence below threshold → best guess persisted as PENDING_REVIEW with exactly one clarification question ("Is this Groceries or Household?")
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`ParseResult.needsClarification/clarificationQuestion` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Questions are generated text shown to the user; bounded length, no raw injection echo.

## Eval impact
Suite `whatsapp_text` multi-item and clarification cases.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-045

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
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
