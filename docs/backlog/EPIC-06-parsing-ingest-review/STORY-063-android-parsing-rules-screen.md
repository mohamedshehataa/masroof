---
id: STORY-063
epic: EPIC-06
title: "Android More → Parsing rules: merchant rules and SMS templates incl. proposed"
phase: 3
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-040, STORY-051, STORY-044, STORY-046]
reqs: [REQ-AND-023]
implements: [PROMPT.md §11 Navigation 5 (Parsing rules)]
owner_agent: android-engineer
---

# STORY-063 — Android More → Parsing rules: merchant rules and SMS templates incl. proposed

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Same as STORY-062 natively.

## Requirements and acceptance criteria
### REQ-AND-023 — Lists and editors for merchant rules and SMS templates; approve/reject PROPOSED templates
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/merchant-rules*`, `/api/sms-templates*`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Admin-only.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-040, STORY-051, STORY-044, STORY-046

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
