---
id: STORY-157
epic: EPIC-16
title: "Category-creep card"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-150, STORY-094, STORY-099]
reqs: [REQ-AN-118, REQ-AN-119]
implements: ["Owner request 2026-10-25 (category creep over the months)", "PROMPT.md §10 item 2 (month-over-month change per category)", "PROMPT.md §10 item 10 (anomalies, kept separate)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-157 — Category-creep card

## Goal
The Habits report names categories whose spending has risen steadily for several months, and ignores one-off
spikes, which STORY-099 already reports as anomalies (owner request 2026-10-25; PROMPT §10 items 2 and 10).

## Requirements and acceptance criteria
### REQ-AN-118 — Creep detection
For each top-level category (subcategories roll up; essential and discretionary alike), in the requested scope, let
`T1..T4` be its eligible spend (STORY-150 REQ-AN-103) in the last 4 complete active periods, oldest first. Let
`g = T4 − T1` and `s_i = T_{i+1} − T_i`. The category is *creeping* when all of these hold:
1. `T1 ≥ 20,000` (200 EGP; small categories never creep);
2. every step is at least +5 %: `100·T_{i+1} ≥ 105·T_i` for i = 1, 2, 3;
3. the total rise is at least +20 %: `100·g ≥ 20·T1`;
4. no single step carries most of the rise: `10·max(s_i) ≤ 6·g`;
5. no single purchase explains it: the largest single net expense `L` of that category in period 4 satisfies `2·L < g`.
`growthBps = ⌊g × 10,000 / T1⌋`. Creeping categories are sorted by `g` descending, then `growthBps` descending, then
`sortOrder`; at most 3 are returned.
**Given** Dining out totals 1,800 / 1,950 / 2,150 / 2,400 EGP and its largest period-4 purchase is 280 EGP **When**
the report is built **Then** Dining out is creeping with `g = 60,000` and `growthBps = 3,333`.
**Given** Electronics totals 1,000 / 1,100 / 1,200 / 4,000 EGP (a 2,700 EGP phone in period 4) **Then** it is not
creeping (rule 4 and rule 5 fail); the same data still produces STORY-099's anomaly for that purchase.
Unhappy paths:
- **Given** Clothing 500 / 0 / 700 / 900 EGP **Then** not creeping (a gap breaks the chain).
- **Given** Groceries 6,000 / 6,200 / 6,350 / 6,500 EGP **Then** not creeping (step 2 is +2.4 %).
- **Given** Dining out above but its largest period-4 purchase is 320 EGP **Then** not creeping (`2·32,000 ≥ 60,000`).
- **Given** a linked refund of 300 EGP lowers period 4 to 2,100 **Then** step 3 fails and Dining out is not creeping.
- **Given** fewer than 4 complete active periods **Then** `INSUFFICIENT_DATA {reason: "NOT_ENOUGH_PERIODS", have, need: 4}`.
- **Given** an expense is moved from Dining out to Groceries **When** the next request arrives **Then** both
  categories' totals change accordingly.

### REQ-AN-119 — Creep card output and text
Card `kind = CATEGORY_CREEP`. `messageKey = habits.creep.found` with `highlight = true` when at least one category
is creeping, else `habits.creep.none` with `highlight = false`.
`detail = {periods: [4 × PeriodRange], categories: [≤ 3 × {category, totals: [T1..T4], growthBps, largestSingle}]}`;
`drillDown = {from: period 1 start, to: period 4 end, categoryIds: [first creeping category]}`.
`params`: `category` (CategoryRef of the first), `first` (T1), `last` (T4) (Money), `growthPct`
(= ⌊growthBps / 100⌋), `steps` (3), `periods` (4), `moreCount` (other creeping categories, 0–2). Clients apply
plural rules (English one/other, Arabic CLDR forms) to count params.

| key | English | Arabic |
|---|---|---|
| `habits.creep.found` | {category} has gone up {steps} months in a row: from {first} to {last} (+{growthPct}%). | {category} بيزيد {steps} شهور ورا بعض: من {first} لـ {last} (+{growthPct}٪). |
| `habits.creep.more` (appended when `moreCount > 0`) | {moreCount} more categories are rising too. | و{moreCount} فئات تانية بتزيد كمان. |
| `habits.creep.none` | No category has been rising steadily over the last {periods} months. | مفيش فئة بتزيد باستمرار في آخر {periods} شهور. |
| `habits.creep.insufficient` | We need {need} full months to spot steady rises; {have} so far. | محتاجين {need} شهور كاملة عشان نشوف الزيادة المستمرة، لحد دلوقتي {have}. |

**Given** the Dining out example **When** an English client renders the card **Then** "Dining out has gone up 3
months in a row: from 1,800 EGP to 2,400 EGP (+33%)."
Unhappy paths:
- **Given** `scope=me` **Then** totals are the caller's part only and the text never mentions the other member.
- **Given** the creeping category is archived after period 4 **Then** it is still reported (its history is real).

## UX states (UI stories only)
N/A (rendered by STORY-161/163).

## Contract impact
`HabitCardKind` gains `CATEGORY_CREEP`; `CategoryCreepDetail`. Additive.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Aggregates only, scope-limited. No LLM.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3. Reuse STORY-094's per-category period totals; do not reuse STORY-099's z-score logic.

## Dependencies
STORY-150 (shared rules), STORY-094 (per-category totals), STORY-099 (anomalies; the spike case must stay there).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 80 production + ≈ 170 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
