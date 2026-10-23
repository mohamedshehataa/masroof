---
id: STORY-036
epic: EPIC-04
title: Android Add sheet — Quick add with amount keypad, category grid, member toggle, date, note, account
phase: 2
status: READY
size: M
modules: [android]
depends_on: [STORY-033, STORY-020, STORY-013, STORY-014]
reqs: [REQ-AND-013]
implements: ["PROMPT.md §11 Navigation 3 (Add: Quick add)", "PROMPT.md §13 (Maestro: quick add)"]
owner_agent: android-engineer
---

# STORY-036 — Android Add sheet: Quick add

## Goal
Same quick-add flow as the PWA (STORY-028) as a native bottom sheet; Scan receipt and Paste text entries are placeholders until STORY-061 (Phase 3).

## Requirements and acceptance criteria
### REQ-AND-013 — Quick add form
**Given** the Add tab/FAB **Then** a `ModalBottomSheet` with entries Scan receipt (disabled, "coming soon"), Quick add, Paste text (disabled); Quick add step 1 shows a large amount display with a custom numeric keypad (digits, one `.`, backspace; Arabic-Indic glyphs when the setting is on, Western value internally) and Next enabled when amount > 0; step 2 shows a category grid (recent/frequent first from the last 100 cached expenses, then alphabetical in the UI language), member toggle (default = signed-in member), date/time pickers (default now, Cairo), note (≤ 500, counter), account picker (chosen member's accounts first), payment-method segmented control (CASH default) and a type overflow (EXPENSE/INCOME/TRANSFER/CASH_WITHDRAWAL); **When** Save is tapped **Then** `POST /api/expenses` with an `Idempotency-Key`, sheet closes, snackbar "Saved · 200 EGP · Fuel · Undo", Home/Transactions refresh; "Save & add another" keeps member/date.
**Given** the Maestro flow `quick-add.yaml` **Then** it adds `200` Fuel and asserts the row appears in Transactions (S9/S13 e2e).
Unhappy path: **Given** `422` **Then** field errors; **Given** a network failure **Then** the entry is written to the Room outbox (STORY-038) and the snackbar says "Saved offline — will sync"; if STORY-038 is not yet merged, Save shows "Couldn't save — retry" reusing the same key; **Given** amount `0` **Then** Next disabled; **Given** a future date > 1 day **Then** inline error.

## UX states (UI stories only)
empty: N/A · loading: grid shimmer; Save progress · error: inline + snackbar · offline: outbox path · RTL: grid flows RTL, segmented controls mirrored · dark: theme; keypad keys ≥ 48 dp with content descriptions; Roborazzi matrix.

## Contract impact
Uses `POST /api/expenses`, `GET /api/categories`, `/api/accounts`, `/api/members`. None new.

## Data / migration impact
None here (outbox table in STORY-038).

## Security / privacy / LLM-safety impact
None.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-033, STORY-020, STORY-013, STORY-014.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
