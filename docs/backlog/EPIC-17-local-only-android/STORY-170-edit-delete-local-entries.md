---
id: STORY-170
epic: EPIC-17
title: Confirm, edit and delete any entry on the phone, with Undo and a paid-by label
phase: 2
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-169]
reqs: [REQ-EXP-044, REQ-EXP-045, REQ-EXP-046, REQ-EXP-047, REQ-AND-134]
implements: ["ADR-0009 D3 (soft delete for Undo, purge after 30 days; paidByMemberId)", "PROMPT.md §11 Transaction detail (edit, delete/restore)", "ADR-0009 D1 §10 item 3 (paid-by label only, no settlement)"]
owner_agent: android-engineer
---

# STORY-170 - Confirm, edit and delete any entry on the phone, with Undo and a paid-by label

## Goal
The edit screen shipped in STORY-166 (descoped; it called `PATCH`/`DELETE /api/expenses/{id}`) now works on Room: one
tap confirms a "pending review" entry, any field can be fixed, a delete can be undone, and an entry can say who paid
(an optional member label; no splits or settlement, ADR-0009 D1). Totals follow immediately.

## Requirements and acceptance criteria
### REQ-EXP-044 - Confirm a pending-review entry
**Given** an entry with `status = PENDING_REVIEW` (e.g. a received transfer of 1,250 EGP captured from SMS) **When** the
user opens it and taps Confirm **Then** the row has `status = CONFIRMED` and a new `updatedAtMillis`, the "pending
review" label is gone from Transactions and Home, the screen closes, and no HTTP request is made.
Unhappy path: **Given** the entry was deleted (e.g. by its Undo notification) while the screen was open **When** the user
taps Confirm **Then** the screen shows "This entry no longer exists" and closes; nothing is written.

### REQ-EXP-045 - Edit and save
**Given** the edit screen **When** the user changes type (expense / income / transfer / refund), amount, date, category,
merchant, notes (at most 500 characters) or paid-by and taps Save **Then** only the row's changed columns and
`updatedAtMillis` are written in one transaction, saving a `PENDING_REVIEW` entry also confirms it, and an amount typed
as `5000` or `٥٠٠٠` is stored as `500000` minor units. A date changed to another day is stored as Cairo noon with
`timeKnown = 0`; an unchanged date keeps the original instant and `timeKnown`.
**Given** nothing was changed **When** the user taps Save **Then** nothing is written (`updatedAtMillis` unchanged).
Unhappy path: **Given** amount `0`, empty, negative or above 1,000,000,000,000 minor units, or notes over 500 characters
**When** Save is tapped **Then** the field shows its error, the edits are kept and nothing is written.
Unhappy path: **Given** an entry in a foreign currency (`USD`) **When** edited **Then** the currency is kept and shown;
the amount is never converted.

### REQ-EXP-046 - Delete with Undo, purge after 30 days
**Given** the edit screen **When** the user taps Delete and confirms **Then** the row gets `deletedAtMillis` = now, it
disappears from every list, total and chart at once, and a snackbar "Deleted - Undo" is shown for at least 5 seconds;
**When** Undo is tapped **Then** `deletedAtMillis` is null again and the entry is back with all its fields, sources and
receipts.
**Given** a deleted entry whose `deletedAtMillis` is more than 30 days ago **When** the daily purge runs (app start or a
WorkManager periodic job) **Then** the row and its `expense_source` rows are removed (receipts: STORY-174).
Unhappy path: **Given** the delete dialog **When** the user taps Cancel **Then** nothing is written.
Unhappy path: **Given** a deleted entry that came from an SMS **When** the same SMS is delivered again before the purge
**Then** no new entry is created (its source row still exists; REQ-SMS-096).

### REQ-EXP-047 - Paid-by is an optional member label
**Given** two or more members (e.g. "Me" and "Sara") **When** the user opens Add or Edit **Then** a "Paid by" choice
lists the members plus "Not set", the default is "Not set", and the chosen member's id is stored in `paidByMemberId`
and shown as a small chip on the Transactions row. Totals, Insights and budgets never split or settle by member.
Unhappy path: **Given** only one member **When** Add or Edit opens **Then** no "Paid by" choice is shown and
`paidByMemberId` stays null.
Unhappy path: **Given** an entry whose `paidByMemberId` points to a member that no longer exists **When** it is shown
**Then** no chip is shown and the edit screen shows "Not set"; nothing crashes.

### REQ-AND-134 - One write at a time
**Given** a save, confirm or delete in progress **When** the user taps any of the buttons again (double tap) **Then** the
buttons are disabled until it ends and exactly one write happens (one `updatedAtMillis` change, one delete).
Unhappy path: **Given** the write throws (test fake) **Then** "Couldn't save, try again" is shown above the buttons, the
edits are kept, and the row is unchanged.

## UX states (UI stories only)
- loading: the form opens from Room at once; buttons disabled during a write.
- error: inline message above the buttons, edits kept.
- empty: n/a (opened from a row).
- offline: identical to online, no network message.
- RTL / dark: paid-by chip and snackbar mirrored; strings in `values` + `values-ar` (Egyptian Arabic); 48 dp targets;
  TalkBack reads "Paid by Sara" on the row; screen keeps `FLAG_SECURE` as today.

## Contract impact
None. `PATCH`/`DELETE /api/expenses/{id}` are no longer called.

## Data / migration impact
No schema change (uses v9 `deletedAtMillis`, `paidByMemberId`, `updatedAtMillis`). The daily purge deletes rows soft
deleted more than 30 days ago.

## Security / privacy / LLM-safety impact
Merchant text, notes and amounts are never logged (`ExpenseChanges.toString` stays redacted). Undo restores only the
user's own soft-deleted row. No new permission.

## Eval impact
None.

## Design notes (architect)
ADR-0009 D3 (soft delete, purge after 30 days). Edit form and validation are reused from STORY-166.

## Dependencies
STORY-169

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: remote calls replaced by DAO writes ≈ 120, paid-by + purge ≈ 60, tests ≈ 200)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D3), reuses STORY-166 |
| 2026-10-28 | 5 implement | android-engineer | IN_PROGRESS - edit/confirm/delete(soft)+Undo/paid-by on Room, purge worker; PR open |
| 2026-10-28 | 6+7 verify | qa-verifier | PASS loop 2 (round 1 fixed amount limit, double tap, notes error, undo buffer); merged |
