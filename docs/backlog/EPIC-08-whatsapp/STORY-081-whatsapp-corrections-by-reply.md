---
id: STORY-081
epic: EPIC-08
title: "Corrections by replying to a bot confirmation (amount, category, person)"
phase: 5
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-077, STORY-051]
reqs: [REQ-WA-060, REQ-WA-061, REQ-WA-062]
implements: [PROMPT.md §3.4 (Corrections replying to a bot confirmation), PROMPT.md §6 (Learning from corrections)]
owner_agent: ai-parsing-engineer
---

# STORY-081 — Corrections by replying to a bot confirmation (amount, category, person)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Fixing an entry is one reply in the chat (PROMPT §3.4).

## Requirements and acceptance criteria
### REQ-WA-060 — Reply 'لا دي كانت 250 مش 200' to a confirmation updates the amount of that entry
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-061 — Reply 'category groceries' recategorizes and learns a merchant rule
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-062 — Reply "make it Mai's" changes paid-by member
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
Uses reply linkage from STORY-078.

## Security / privacy / LLM-safety impact
Correction text is untrusted; bounded edits; only entries of the same household.

## Eval impact
Suite `whatsapp_text` corrections cases.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-077, STORY-051

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 5 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
