---
id: STORY-131
epic: EPIC-14
title: "Android accessibility pass and performance budgets (cold start, APK size, Baseline Profile)"
phase: 9
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-112]
reqs: [REQ-AND-100, REQ-AND-101]
implements: [PROMPT.md §11 (Accessibility), PROMPT.md S9 (Android non-functional)]
owner_agent: android-engineer
---

# STORY-131 — Android accessibility pass and performance budgets (cold start, APK size, Baseline Profile)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The Android app meets its a11y and performance budgets (PROMPT S9).

## Requirements and acceptance criteria
### REQ-AND-100 — Content descriptions, dynamic type, 48dp targets, contrast AA verified by Accessibility Scanner/Compose tests
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-101 — Cold start < 1.5 s (Macrobenchmark with Baseline Profile), APK ≤ 25 MB, no ANRs in Maestro runs
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
font scale 1.5 review

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-112

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 9 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
