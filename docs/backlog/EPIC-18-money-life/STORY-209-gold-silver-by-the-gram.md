---
id: STORY-209
epic: EPIC-18
title: Gold and silver by the gram - lots bought and sold, today's price, value and gain, goals backed by gold
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-205, STORY-204]
reqs: [REQ-EXP-063, REQ-AN-173, REQ-AN-174]
implements: ["Say teardown items 5, 8, 20 (gold valued by purity, lots, goals that grow with your gold)"]
owner_agent: android-engineer
---

# STORY-209 - Gold and silver by the gram - lots bought and sold, today's price, value and gain, goals backed by gold

## Goal
Egyptian households hold savings in gold. Mohamed records each purchase (grams, karat, what he paid), types today's
gram price once (no network, so no live feed), and sees what the gold is worth now, the average price paid and the gain.
A goal (STORY-204) can be backed by a gold holding so its progress grows with the gold price.

## Requirements and acceptance criteria
### REQ-EXP-063 - Gold or silver holding with buy and sell lots
**Given** Wealth -> Add -> Gold **When** the user names it "Wedding set", picks 21K (gold: 24, 22, 21, 18; silver:
999, 925), and adds a buy lot of 30 g for 120,000 EGP on 2026-05-09 **Then** one `asset` (GOLD, karat 21) and one
`asset_lot` (BUY, quantityMilli 30000, totalMinor 12,000,000) are stored; more lots can be added, edited or deleted;
"Record a sale" adds a SELL lot (grams and amount received). Optionally a lot links to an existing entry (e.g. the
captured card payment), which is then shown as "gold purchase" and excluded from spending via
`expense.excludedFromTotals = 1` with Undo (buying gold is saving, not spending).
Unhappy path: selling more grams than held -> "You hold only X g"; grams or amount empty/0/negative -> field error.

### REQ-AN-173 - Value with today's typed price, average cost and gain
**Given** lots 30 g bought for 120,000 and 10 g for 45,000, a sale of 5 g for 24,000, and a typed 21K price of 4,800 EGP
per gram today **Then** held = 35 g; value = 35 x 4,800 = 168,000; average paid per gram (average-cost method) =
(120,000 + 45,000) / 40 = 4,125; cost of held = 35 x 4,125 = 144,375; gain = 23,625 (+16.36%, integer basis points,
half away from zero); realised gain on the sale = 24,000 - 5 x 4,125 = 3,375. "Gold price today" lets the user type a
price per gram per karat (stored in `metal_price` with the date); a price older than 7 days shows "Price from 3 Nov -
update"; with no price at all the value falls back to cost with "Add today's price". The holding's value feeds net worth
(STORY-205) under "Gold & silver"; "hide from totals" works as for other assets. Milligram integers throughout, no
Double/Float.

### REQ-AN-174 - A goal backed by gold
**Given** goal "Sahel chalet" (STORY-204) **When** the user links the "Wedding set" holding to it **Then** `asset.goalId`
is set and the goal's saved amount = its own movements + the holding's current value, shown as "including gold worth
168,000"; progress, monthly need and on-track update when the price changes. Unlinking asks first. One holding backs at
most one goal.
Unhappy path: deleting a linked holding unlinks it first (FK SET NULL) and the goal's saved amount drops accordingly.

## UX states (UI stories only)
Gold/silver detail (held grams, value, average paid, gain, lots list, price row), add/edit lot sheet, today's price
sheet per karat, goal link picker. RTL, dark, Arabic-Indic digits. Strings in `strings_gold.xml`.

## Contract impact
None.

## Data / migration impact
None (`asset`, `asset_lot`, `metal_price`, `expense.excludedFromTotals` from STORY-201).

## Security / privacy / LLM-safety impact
None (local data, no price feed).

## Eval impact
None.

## Design notes (architect)
Pure `MetalMath` (average cost, value, gain, realised gain) with table-driven tests; extends `data/wealth` and
`ui/wealth`; goal saved amount computed in `GoalMath` with an optional asset value input.

## Dependencies
STORY-205, STORY-204

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M (maths ~150, screens ~350, tests ~300)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 3 |
