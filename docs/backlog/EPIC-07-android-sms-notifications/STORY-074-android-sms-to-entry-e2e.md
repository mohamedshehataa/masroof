---
id: STORY-074
epic: EPIC-07
title: "Maestro e2e: emulator SMS becomes an entry"
phase: 4
status: DESCOPED
size: S
modules: [android, infra]
depends_on: [STORY-067, STORY-056, STORY-044]
reqs: [REQ-AND-050]
implements: ["PROMPT.md §13 (Android: Maestro e2e incl. emulator SMS)", "PROMPT.md S9 (Maestro: SMS-to-entry)"]
owner_agent: android-engineer
---

# STORY-074 — Maestro e2e: emulator SMS becomes an entry

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The whole SMS path is proven end to end on every nightly run (PROMPT §13).

## Requirements and acceptance criteria
### REQ-AND-050 — Maestro flow sends `adb emu sms send CIB "...EGP 1,250.00 at CARREFOUR MAADI..."` and asserts the entry appears in Transactions within 60 s against `make up`
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Fixture SMS only (anonymized).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-067, STORY-056, STORY-044

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 4 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
