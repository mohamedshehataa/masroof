---
id: STORY-181
epic: EPIC-17
title: Create, edit and delete budgets on the phone and see their progress offline
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-180]
reqs: [REQ-AND-136, REQ-AND-137, REQ-AND-138]
implements: ["PROMPT.md §10 item 8 and §11 Budgets (Android) as shipped in STORY-092/STORY-112", "ADR-0009 D1 (§10 item 8 local), D3 migration rules"]
owner_agent: android-engineer
---

# STORY-181 - Create, edit and delete budgets on the phone and see their progress offline

## Goal
The Budgets screen (STORY-112, shipped against `/api/budgets`) keeps its look but stores budgets in Room and shows
progress from the on-device engine (STORY-180), so budgets include local entries and work with no server. Same rules as
the shipped server: MONTHLY or WEEKLY, one budget per category and period, the household total as "All spending",
rollover one period deep.

## Requirements and acceptance criteria
### REQ-AND-136 - Create a budget
**Given** More -> Budgets **When** the user adds "Groceries / بقالة", MONTHLY, `4000`, rollover off **Then** one budget row
is stored (`amountMinor = 400000`, `EGP`, period MONTHLY, category Groceries, thresholds 50/80/100, created now), the list
shows it with its progress at once, and no HTTP request is made. Arabic-Indic `٤٠٠٠` gives the same amount.
Unhappy path: **Given** a budget already exists for Groceries MONTHLY **When** another Groceries MONTHLY is saved **Then**
"A budget for this category and period already exists" is shown and nothing changes (no silent overwrite; the 409
adopt logic of PR #69 is not needed locally and is removed). A WEEKLY Groceries budget next to the MONTHLY one is
allowed.
Unhappy path: **Given** amount empty, 0, negative or above 1,000,000,000,000 minor units **Then** the field error is shown
and nothing is stored.
Unhappy path: **Given** "All spending" already budgeted **Then** the Add dialog starts on the first free category, and
when every category and the total are taken the Add button is disabled with "Every category already has a budget".

### REQ-AND-137 - Edit and delete a budget
**Given** a budget **When** the user changes the amount or rollover and saves **Then** only those values change (category
and period are fixed after create, as on the server) and progress updates at once. **When** the user deletes it and
confirms **Then** it is removed and Home's remaining-budget figure updates.
Unhappy path: **Given** the delete dialog **When** Cancel is tapped **Then** nothing changes.
Unhappy path: **Given** the category of a budget is archived or missing **Then** the budget still shows (named
"Deleted category") and can be deleted.

### REQ-AND-138 - Progress is shown from the phone, offline
**Given** budgets and entries **When** the user adds a 500 EGP Groceries entry **Then** the Groceries progress bar and the
Home budget figure reflect it without a refresh, using REQ-AN-153..155 (spent, remaining, progress, alerts reached,
on track, days left). **Given** airplane mode **Then** everything is identical; no "offline" or "last updated" text.
Unhappy path: **Given** a budget whose period has no eligible spend **Then** it shows 0% and "On track".
Unhappy path: **Given** the engine throws (test fake) **Then** the list shows the budgets with "Couldn't compute
progress" and Retry instead of crashing.

## UX states (UI stories only)
- empty: "No budgets yet. Add one to see your progress."
- loading: Room-backed, no spinner beyond the first frame.
- error: inline field errors in the dialog; REQ-AND-138 message for computation errors.
- offline: identical to online.
- RTL / dark: existing Budgets components (progress bars mirror), new strings in `values` + `values-ar`, 48 dp targets,
  TalkBack reads "Groceries, 75 percent used, 1,000 pounds left".

## Contract impact
None. `/api/budgets*` and `/api/analytics/budgets` are no longer called.

## Data / migration impact
Needs a local `budget` table, which ADR-0009 D3 does not list (the ADR put budgets after the MVP). Open design point for
the architect: fold it into `MIGRATION_8_9` if STORY-168 has not merged yet, otherwise a new additive Room version with
its own hand-written migration, exported schema and `MigrationTestHelper` test (the v10 contract step then moves to the
next number). Backup (STORY-183) must include budgets.

## Security / privacy / LLM-safety impact
None (local data, no network, amounts not logged).

## Eval impact
None.

## Design notes (architect)
See "Data / migration impact". Budget rules come from STORY-180.

## Dependencies
STORY-180

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact (table placement is the architect's open point, flagged)
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: table + DAO + migration ≈ 90, repository swap ≈ 70, tests ≈ 200)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot, reuses STORY-092/112 budgets |
