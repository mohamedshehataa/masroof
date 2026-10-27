---
id: STORY-195
epic: EPIC-17
title: Savings goals - set money aside ahead for a known expense
phase: 7
status: DRAFT
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-181]
reqs: [REQ-AN-159]
implements: ["Owner idea (goals / saving ahead), not in PROMPT.md Part I; scope to be confirmed by the owner at /spec"]
owner_agent: android-engineer
---

# STORY-195 - Savings goals - set money aside ahead for a known expense

> **DRAFT stub** - owner raised "goals / saving ahead" earlier; no Part I section covers it. Product questions (how money
> "set aside" is recorded, whether it reduces the spendable budget) are open for the owner. REQ id reserved.

## Goal
The household plans for a known future cost (school fees, Eid, a car service) by saving a monthly amount towards it.

## Requirements and acceptance criteria
### REQ-AN-159 - A goal with a target amount and date shows the monthly amount needed and progress
Given a goal "School fees" of 30,000 EGP due 2027-09-01 Cairo, when created, then the app shows the monthly amount
needed (integer piasters, half away from zero, over the remaining periods with the month start day), progress from
contributions the user records, and "behind / on track"; nothing is moved in any bank account.

## UX states (UI stories only)
Goals list, empty, behind/on track, RTL/dark.

## Contract impact
None.

## Data / migration impact
New local table(s) (architect).

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
To be filled after the owner answers the open product questions.

## Dependencies
STORY-181

## Definition of Ready checklist
- [ ] Goal + Part I links (no Part I section; owner confirmation needed)
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headline only)
- [x] UX states (UI stories)
- [x] Contract impact
- [ ] Data / migration impact (open)
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - owner idea, scope to confirm |
