---
id: STORY-166
epic: EPIC-04
title: Android - tap a synced transaction to confirm it, edit it or delete it
phase: 2
status: DESCOPED
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-035, STORY-022]
reqs: [REQ-AND-120, REQ-AND-121, REQ-AND-122, REQ-AND-123, REQ-AND-124, REQ-AND-125, REQ-AND-126]
implements: ["PROMPT.md §11 Key screens (Transaction detail), slice of STORY-037"]
owner_agent: android-engineer
---

# STORY-166 - Edit, confirm and delete a synced transaction on Android

## Goal
A captured InstaPay transfer shows as "pending review" and could only be undone. Tapping any synced row (Transactions, Home recent) opens an edit screen: Confirm in one tap, fix type / amount / date / category / merchant / notes, or delete. A slice of STORY-037 (no split, audit or sources timeline).

## Requirements and acceptance criteria
### REQ-AND-120 - Confirm a pending-review item
**Given** a synced row in PENDING_REVIEW **When** the user taps it and taps Confirm **Then** `PATCH /api/expenses/{id}` is sent with `status=CONFIRMED` only, the cached row becomes CONFIRMED immediately, Home and Insights are signalled, the screen closes and the "pending review" label is gone.

### REQ-AND-121 - Edit and save
**Given** the edit screen **When** the user changes type, amount, date, category, merchant or notes and taps Save **Then** only the changed fields are sent (amount as minor units + currency, date as the Cairo business date at noon, notes capped at 500), saving a pending-review row also confirms it, the cache is updated at once and the screen closes. Saving with nothing changed sends nothing.

### REQ-AND-122 - Delete
**Given** the edit screen **When** the user taps Delete and confirms the dialog **Then** `DELETE /api/expenses/{id}` is sent once, the row leaves the cache and the totals, and the screen closes; Cancel sends nothing; a 404 counts as deleted.

### REQ-AND-123 - Offline, timeout, auth
**Given** no connection or no answer within the screen deadline **Then** the screen shows "Couldn't save, check your connection", keeps the user's edits, and is not stuck loading. **Given** 401 **Then** the sign-in message is shown (the session's single refresh path is untouched).

### REQ-AND-124 - Conflict and invalid values
**Given** a 409 **Then** the latest server version is fetched, cached and shown with a "changed elsewhere" notice. **Given** a 400/422 **Then** the offending field (amount, date) is marked when the server names it, otherwise a general message is shown; edits are kept. (The backend ignores `version` on PATCH today and the Expense schema returns none, so none is sent.)

### REQ-AND-125 - One call at a time, local rows stay local
**Given** a call in flight **Then** every button is disabled and a repeated tap sends no second PATCH or DELETE. **Given** a row that is still queued (`pending`) or `failed` **Then** it does not open the edit screen and keeps Retry / Delete; the Captured "Undo" button keeps working.

### REQ-AND-126 - Totals follow the type
**Given** an EXPENSE changed to INCOME or TRANSFER (or back) **Then** the month total drops or gains that amount; only EXPENSE counts (REQ-SMS-092).

## UX states
loading: form is shown from the cache at once, buttons disabled while a call runs · error: inline message above the buttons, edits kept · offline: same message · RTL/dark: standard theme, 48dp targets, Arabic strings (Egyptian).

## Contract impact
None. Uses `PATCH`/`DELETE /api/expenses/{id}` and `GET` after a 409.

## Data / migration impact
None (no new Room column).

## Security / privacy / LLM-safety impact
Amounts, merchant text and notes are never logged; `ExpenseChanges.toString` omits them. No manifest or permission change.

## Eval impact
None.

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | speed-mode build | android-engineer | IN_PROGRESS |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
