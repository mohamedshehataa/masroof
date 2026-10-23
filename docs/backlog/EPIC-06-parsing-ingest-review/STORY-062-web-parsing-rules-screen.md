---
id: STORY-062
epic: EPIC-06
title: "Web More → Parsing rules: merchant rules and SMS templates incl. proposed"
phase: 3
status: DRAFT
size: M
modules: [web]
depends_on: [STORY-031, STORY-051, STORY-044, STORY-046]
reqs: [REQ-UI-033]
implements: [PROMPT.md §11 Navigation 5 (Parsing rules), PROMPT.md §4.3 (admin approves in Settings → Parsing rules)]
owner_agent: web-engineer
---

# STORY-062 — Web More → Parsing rules: merchant rules and SMS templates incl. proposed

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The admin sees and tunes what the deterministic parser knows (PROMPT §11 More).

## Requirements and acceptance criteria
### REQ-UI-033 — Lists and editors for merchant rules and SMS templates; approve/reject PROPOSED templates; test a template against a sample text
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/merchant-rules*`, `/api/sms-templates*`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Admin-only; regex preview runs server-side (dry-run) to keep ReDoS protection.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-031, STORY-051, STORY-044, STORY-046

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
