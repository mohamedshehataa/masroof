---
id: STORY-028
epic: EPIC-03
title: Web Add sheet — Quick add with amount keypad, category grid, member toggle, date, note, account
phase: 2
status: READY
size: M
modules: [web]
depends_on: [STORY-017, STORY-018, STORY-020, STORY-013, STORY-014]
reqs: [REQ-UI-023]
implements: ["PROMPT.md §11 Navigation 3 (Add: Quick add)", "PROMPT.md §1 item 6 (manual entry)"]
owner_agent: web-engineer
---

# STORY-028 — Web Add sheet: Quick add

## Goal
A member records an expense in under ten seconds: amount first, then a category, with sensible defaults (PROMPT §11 Add). The sheet's other entries — Scan receipt and Paste text — arrive in STORY-060 (Phase 3) and show as disabled placeholders with "coming soon" copy until then.

## Requirements and acceptance criteria
### REQ-UI-023 — Quick add form
**Given** the centre FAB or `/add` **Then** a bottom sheet (full-screen on < 600 px) opens on step 1: a large amount display with a numeric keypad (digits, `.` once, backspace, Arabic-Indic digits rendered when that setting is on but the value is Western internally), currency fixed to the household base currency; "Next" is enabled when amount > 0. Step 2: a category grid (recent/frequent first — computed client-side from the last 100 expenses, then the rest alphabetically in the UI language, 3–4 columns, icon + name), a member toggle defaulting to the signed-in member, a date/time field defaulting to now (Cairo), an optional note (≤ 500 chars, counter), an optional account picker (the chosen member's accounts first) and a payment-method segmented control (CASH default, CARD, WALLET, INSTAPAY, BANK_TRANSFER); type switch EXPENSE / INCOME / TRANSFER / CASH_WITHDRAWAL in an overflow menu.
**When** "Save" is tapped **Then** `POST /api/expenses` is sent with an `Idempotency-Key`, the sheet closes, a snackbar "Saved · 200 EGP · Fuel" with "Undo" (soft delete) appears, and Home/Transactions queries are invalidated; "Save & add another" keeps the sheet open with member/date preserved.
Unhappy path: **Given** `422` **Then** field errors render beside the field named in `errors[]`; **Given** network failure **Then** the button re-enables with "Couldn't save — retry" and the same `Idempotency-Key` is reused (no duplicate on retry); **Given** the user is offline **Then** Save is disabled with "Offline — queued saving arrives in Phase 8" (no silent data loss; decision recorded, offline queue is STORY-115); **Given** amount `0` or `.` **Then** Next stays disabled; **Given** a future date > 1 day **Then** inline error.

## UX states (UI stories only)
empty: N/A (form) · loading: category grid skeleton; Save spinner · error: inline + snackbar · offline: Save disabled + explanation · RTL: keypad layout unchanged (digits), category grid flows right-to-left, segmented controls mirrored · dark: tokens; keypad keys ≥ 48 px, focus order and labels for screen readers (axe clean).

## Contract impact
Uses `POST /api/expenses`, `GET /api/categories`, `GET /api/accounts`, `GET /api/members` (v0 + STORY-015 addition). None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (manual input; notes are user text sent over HTTPS).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-017, STORY-018, STORY-020, STORY-013, STORY-014.

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
