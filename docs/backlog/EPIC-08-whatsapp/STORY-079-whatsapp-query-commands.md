---
id: STORY-079
epic: EPIC-08
title: "WhatsApp query commands: total, category spend, budget, pending, week summary, help (fuzzy, '/' prefix)"
phase: 5
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-077, STORY-025]
reqs: [REQ-WA-050, REQ-WA-051, REQ-WA-052, REQ-WA-053, REQ-WA-054, REQ-WA-055, REQ-WA-056]
implements: [PROMPT.md §3.4 (Commands table), PROMPT.md S10.1 (commands suite)]
owner_agent: backend-engineer
---

# STORY-079 — WhatsApp query commands: total, category spend, budget, pending, week summary, help (fuzzy, '/' prefix)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Members ask the bot questions in their own words and get numbers back (PROMPT §3.4).

## Requirements and acceptance criteria
### REQ-WA-050 — `total` / `صرفنا كام الشهر ده` → month-to-date total + top categories + vs last month
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-051 — `كام على الأكل؟` → category total this month
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-052 — `budget` → budget status per category ('no budgets set' until Phase 7)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-053 — `pending` → entries awaiting review
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-054 — `week` / `ملخص الأسبوع` → weekly summary
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-055 — `help` / `مساعدة` → short bilingual guide
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-056 — Fuzzy matching in Arabic/English and optional `/` prefix; unknown command → help hint
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`CommandName` enum in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Commands only read household data of the sender's household.

## Eval impact
Suite `commands` 30 → 80 incl. fuzzy variants.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-077, STORY-025

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
