---
id: STORY-080
epic: EPIC-08
title: "WhatsApp mutation commands: undo and edit last"
phase: 5
status: DESCOPED
size: S
modules: [backend]
depends_on: [STORY-079, STORY-022]
reqs: [REQ-WA-057, REQ-WA-058]
implements: ["PROMPT.md §3.4 (Undo, Edit last)"]
owner_agent: backend-engineer
---

# STORY-080 — WhatsApp mutation commands: undo and edit last

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
A wrong entry is fixed from the chat without opening an app (PROMPT §3.4).

## Requirements and acceptance criteria
### REQ-WA-057 — `undo` / `امسح آخر واحدة` soft-deletes the sender's last entry and confirms with Restore hint
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-058 — `edit last 300` updates the amount of the sender's last entry; audit logged
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Only the sender's own last entry; bounded amount per REQ-SEC-022.

## Eval impact
Suite `commands`.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-079, STORY-022

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 5 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
