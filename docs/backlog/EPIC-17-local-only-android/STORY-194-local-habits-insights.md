---
id: STORY-194
epic: EPIC-17
title: Habits insights (payday effect, weekends, late night, small purchases) on the phone
phase: 7
status: DRAFT
risk_tier: low
size: M
modules: [android]
depends_on: [STORY-179]
reqs: [REQ-AN-158]
implements: ["Owner extension 2026-10-25 (EPIC-16 behavioral insights), recomputed on the device per ADR-0009 D2"]
owner_agent: android-engineer
---

# STORY-194 - Habits insights (payday effect, weekends, late night, small purchases) on the phone

> **DRAFT stub** - carries the EPIC-16 ideas (STORY-149..165, all descoped: they assumed backend endpoints, web push and
> WhatsApp nudges). To be split per card at /spec. REQ id reserved.

## Goal
A Habits tab shows the household's patterns with fixed bilingual sentences and integer maths, computed on the phone.

## Requirements and acceptance criteria
### REQ-AN-158 - Habits cards from local data: payday effect, weekend vs weekday, late night, small purchases, category creep
Given at least 2 full periods of entries, when the Habits tab opens, then each card whose rule has enough data shows one
sentence with integer amounts in piasters (e.g. "Weekend days cost 1.8x a weekday"), time-of-day rules use only entries
with `timeKnown = 1`, cards without enough data are hidden, and no LLM or network is used. Nudges are local notifications
only (in-app or system), never WhatsApp/web push.

## UX states (UI stories only)
Cards, not-enough-data state, RTL/dark.

## Contract impact
None.

## Data / migration impact
None expected.

## Security / privacy / LLM-safety impact
None (local rules; no salary amount shown on shared screens).

## Eval impact
None.

## Design notes (architect)
Reuse the rule definitions written in EPIC-16 stories (docs/backlog/EPIC-16-behavioral-insights/README.md).

## Dependencies
STORY-179

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (to be split per card at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, carries EPIC-16 ideas |
