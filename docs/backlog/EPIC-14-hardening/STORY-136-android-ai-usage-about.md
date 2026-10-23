---
id: STORY-136
epic: EPIC-14
title: "Android More → Security (sessions), AI usage & cost, About/diagnostics"
phase: 9
status: DRAFT
size: S
modules: [android]
depends_on: [STORY-040, STORY-050, STORY-129]
reqs: [REQ-AND-095]
implements: [PROMPT.md §11 Navigation 5]
owner_agent: android-engineer
---

# STORY-136 — Android More → Security (sessions), AI usage & cost, About/diagnostics

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The remaining More rows exist on Android (PROMPT §11).

## Requirements and acceptance criteria
### REQ-AND-095 — Security screen (biometric toggle, log out everywhere), AI usage & cost, About/diagnostics (version, update check placeholder)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/settings`, `/auth/logout`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-040, STORY-050, STORY-129

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
