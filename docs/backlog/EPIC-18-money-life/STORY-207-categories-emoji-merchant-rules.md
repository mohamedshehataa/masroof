---
id: STORY-207
epic: EPIC-18
title: Categories with emoji you can add, rename and archive, and merchant rules that file entries for you
phase: 7
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-201]
reqs: [REQ-EXP-060, REQ-EXP-061]
implements: ["Say teardown items 14, 21, 24 (emoji category icons, merchant rules, manage categories)", "Play review asking for custom categories with more emoji", "Categories half of STORY-196 REQ-EXP-050 intent"]
owner_agent: android-engineer
---

# STORY-207 - Categories with emoji you can add, rename and archive, and merchant rules that file entries for you

## Goal
Every category gets an emoji that shows wherever entries appear (today it is a coloured first letter), the household can
add its own categories, and "always put Talabat under Dining out" is a rule the app applies to new captures and,
on request, to past entries. Say has both; its reviewers asked for custom categories with more emoji.

## Requirements and acceptance criteria
### REQ-EXP-060 - Emoji on every category; add, rename, reorder, archive
**Given** the seeded categories **When** the app starts after this story **Then** each seeded category shows a default
emoji (e.g. Groceries 🛒, Dining out 🍔, Car 🚗, Fuel ⛽, Transport 🚕, Bills 🧾, Rent & housing 🏠, Health 🏥,
Education 🎓, Kids 🧸, Clothing 👕, Personal care 💈, Household items 🛋️, Electronics 💻, Entertainment 🎬,
Subscriptions 🔁, Travel ✈️, Gifts & charity 🎁, Fees & charges 🏦, Pets 🐾, Other 📦) written once to
`category.emoji` where it is NULL (a user's choice is never overwritten), and `CategoryAvatar` shows the emoji (falls
back to the letter if NULL). **Given** Settings -> Categories **When** the user adds "Coffee / قهوة" ☕ under Dining out,
renames a category (en and ar), changes its emoji from a grid of at least 80 emoji with search by English/Arabic keyword,
reorders, or archives one **Then** pickers, lists and Insights follow at once; archived categories keep their history and
still roll up; a category in use cannot be deleted, only archived; an unused custom category can be deleted.
Unhappy path: empty name, a duplicate name under the same parent (case- and diacritic-insensitive) -> field error.
System categories can be renamed and archived but never deleted.

### REQ-EXP-061 - Merchant rules: suggest, apply to new entries, optionally to past ones
**Given** an entry with merchant "TALABAT EG" in Groceries **When** the user changes its category to Dining out in Edit
**Then** a prompt asks "Always file Talabat under Dining out?"; Yes stores one `merchant_rule` (merchantKey = the
normalised, digit-free merchant key the app already uses for merchant grouping) and asks "Also change the 6 past
Talabat entries?" (Yes updates those not-deleted entries in one transaction with Undo). **Given** the rule **When** a new
SMS/notification capture or manual entry arrives with that merchant and no category chosen by the user **Then** it is
saved under Dining out. A user-chosen category on a manual entry always wins. Settings -> Merchant rules lists rules
with delete and an "Add rule" form (merchant text + category).
Unhappy path: deleting a category that a rule points to is impossible while in use (archive instead); archiving a
category keeps its rules working; a merchant key that is empty after normalisation cannot get a rule.
Unhappy path: rule application never changes amount, date, type or account, and never logs merchant text above DEBUG.

## UX states (UI stories only)
Categories list (grouped by parent, drag to reorder), add/edit sheet with emoji grid and search, archive confirm;
merchant-rules list, empty state, add form; the Edit-entry prompt. RTL, dark. Strings in `strings_categories.xml`
(en + ar).

## Contract impact
None.

## Data / migration impact
None (columns and table from STORY-201). Default emoji are written by a one-time idempotent startup step, not a
migration.

## Security / privacy / LLM-safety impact
Touches capture (category assignment after parsing): rules run on the structured record only, never on message text.
Security reviewer: confirm no message text reaches `merchant_rule`.

## Eval impact
None for the LLM harness; capture tests with fixtures prove a rule applies and a user choice wins.

## Design notes (architect)
Apply rules in one place that both capture and manual save already pass through (find it; likely `data/ExpenseRepository`
or the capture writer in `ingest/CaptureRows.kt`). `CategoryAvatar` in `ui/components/Components.kt` gets the emoji.

## Dependencies
STORY-201

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M (screens ~350, rules ~120, tests ~350)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 wave 2 |
