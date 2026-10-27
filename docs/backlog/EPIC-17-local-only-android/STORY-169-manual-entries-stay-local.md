---
id: STORY-169
epic: EPIC-17
title: Manual entries are saved on the phone and no sign-in or sign-out can erase them
phase: 2
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-168]
reqs: [REQ-EXP-043, REQ-AND-130, REQ-AND-131, REQ-AND-132, REQ-AND-133]
implements: ["ADR-0009 D3 (Migrate: manual entries write expense + expense_source; data never follows the session)", "PROMPT.md §11 Add (Quick add), Android-specific offline-first"]
owner_agent: android-engineer
---

# STORY-169 - Manual entries are saved on the phone and no sign-in or sign-out can erase them

## Goal
Mohamed types `200 بنزين` style entries in the Add sheet and they are saved in Room at once, with or without network
and with or without a server account. The app opens straight to Home on a fresh install (no sign-in screen), the
expense lists read only Room, entries still waiting in the old upload queue become local entries, and only an explicit
"Erase all data" in Settings can delete data. ADR-0009 D3 "Migrate" and "Data never follows the session". Reuses the
Add sheet of STORY-036 and the Room cache/outbox of STORY-038 (both descoped).

## Requirements and acceptance criteria
### REQ-EXP-043 - A manual entry is saved in Room only
**Given** the Add sheet with amount `200`, category `Fuel / بنزين` and today's date **When** the user taps Save **Then**
one `expense` row exists with `amountMinor = 20000`, `currency = "EGP"`, `type = EXPENSE`, `status = CONFIRMED`,
`occurredAtMillis` = the save instant, `timeKnown = 1`, `createdAtMillis = updatedAtMillis` = the save instant, plus one
`expense_source` row with `channel = MANUAL`, both written in one transaction; the row shows in Transactions and in the
Home list without a refresh; and the app makes zero HTTP requests (asserted with a recording fake HTTP client).
**Given** amount `٢٠٠` (Arabic-Indic digits) or `1,450` **When** saved **Then** `amountMinor` is `20000` / `145000`.
**Given** a past date chosen in the date picker (not today) **When** saved **Then** `occurredAtMillis` is noon in
`Africa/Cairo` of that date (`CairoTime.noonOf`) and `timeKnown = 0`.
Unhappy path: **Given** amount empty, `0`, negative, non-numeric (`abc`) or above 1,000,000,000,000 minor units **When**
the user taps Save **Then** the amount field shows "Enter an amount" / "Amount is too large" and no row is written.
Unhappy path: **Given** airplane mode **When** the user saves **Then** the result is the same as online (no "offline"
message, no queued state, no Retry button).
Unhappy path: **Given** a database write that throws (test fake) **When** the user saves **Then** the sheet stays open
with "Couldn't save, try again", the input is kept, and no `expense` or `expense_source` row exists.

### REQ-AND-130 - The app works without a server account
**Given** a fresh install with no session **When** the app starts **Then** Home is the first screen (no sign-in screen),
and Add, Transactions and Settings work. **Given** a phone that is still signed in **When** the app starts **Then**
Home is shown and the expense list is built from Room only: no `GET /api/expenses` (or any `/api/expenses*` call) is made
on start, on pull-to-refresh or after a save (recording fake HTTP client).
Unhappy path: **Given** a session whose refresh token the server rejects **When** the app starts **Then** no sign-in
screen and no "session expired" message block Home; the local data is shown.

### REQ-AND-131 - Session events never erase local data
**Given** a phone with local expenses, categories, members, review items and learned templates **When** any session
event happens - logout, refresh rejected (session expired), sign-in as a different member, or loss of the Keystore key
that seals the session **Then** the row counts of `expense`, `expense_source`, `category`, `account`, `member`,
`review_item`, `learned_template` and `receipt` are the same before and after, and no file under `filesDir/receipts`
is deleted. Only the session itself is cleared.
Unhappy path: **Given** a capture job running while the user logs out **When** both finish **Then** the job's expense
is still saved (logout no longer cancels capture jobs).

### REQ-AND-132 - "Erase all data" is the only way to delete everything
**Given** Settings **When** the user taps "Erase all data" **Then** a dialog shows the number of entries and receipts
that will be deleted and requires a second explicit confirmation ("Erase" in red); on confirm every Room table is
cleared, `filesDir/receipts` is emptied, learned templates and the local-id key are removed, every "Recorded ... Undo"
notification is cancelled, running capture jobs are cancelled and awaited, and the default categories and the "Me"
member are re-seeded (REQ-EXP-041/042), so the app is as after a fresh install.
Unhappy path: **Given** the dialog **When** the user taps Cancel or presses Back **Then** nothing is deleted (row counts
unchanged).
Unhappy path: **Given** a capture job that commits after the erase started **When** the erase ends **Then** no expense
from that job exists (the capture write re-checks an erase-in-progress flag inside its transaction, as the old gate did).

### REQ-AND-133 - Entries still waiting to upload become local entries
**Given** a v9 database with rows in `outbox` (manual entries never uploaded, incl. one with `failed = 1`) **When** the
app starts **Then** each becomes one local expense (`status = CONFIRMED`, `type = EXPENSE`, same amount, currency,
date, category, account and notes) with an `expense_source` row (`channel = MANUAL`, `localSourceId` = the outbox
`clientId`), the outbox row and the old `pending` cache row are removed in the same transaction, and nothing is
uploaded. No "Retry" or "failed" label is shown any more.
Unhappy path: **Given** the conversion was interrupted after some rows (process killed in the test between rows)
**When** the app starts again **Then** the remaining rows are converted and no expense is duplicated (the `clientId` is
unique in `expense_source`).
Unhappy path: **Given** an outbox row with an amount of 0 or a currency that is not a 3-letter ISO code (corrupt row)
**When** converted **Then** it becomes a `PENDING_REVIEW` expense with the stored values instead of being dropped, so
the user can fix or delete it.

## UX states (UI stories only)
- empty: Home and Transactions show the existing empty state ("No expenses yet - tap + to add one") on a fresh install.
- loading: lists render from Room without a spinner; the Add sheet's Save is disabled while the write runs.
- error: inline "Couldn't save, try again" in the sheet (REQ-EXP-043); Erase errors show "Couldn't erase, nothing was
  changed" only if the whole transaction rolled back.
- offline: no offline banner anywhere (nothing needs the network).
- RTL / dark: new strings ("Erase all data", dialog text) in `values` and `values-ar`, Egyptian Arabic, RTL mirrored,
  the destructive button keeps contrast in dark theme; 48 dp targets.

## Contract impact
None. The app stops calling `/api/expenses*` for lists and manual saves; the endpoints stay on the frozen server.

## Data / migration impact
No schema change (v9 from STORY-168). Data migration in code: `outbox` rows -> `expense` + `expense_source`
(REQ-AND-133). `outbox` and the `pending`/`failed` columns stay until the v10 contract step (STORY-198).

## Security / privacy / LLM-safety impact
Session/trust boundary: `RoomDataWiper` is no longer called by logout, expiry, member switch or Keystore loss; the
session store still wipes its own blob. Erase is user-initiated only and confirmed twice. Amounts and notes are never
logged (`toString` of entities stays redacted). No new permission or exported component. security-reviewer sign-off
(ADR-0009 "L1 ... session decoupling").

## Eval impact
None.

## Design notes (architect)
ADR-0009 D3 ("Migrate", "Data never follows the session"). Capture jobs keep their own gate until STORY-171 turns it
into the "capture enabled" setting; until then captures still upload (release rule in the EPIC-17 README).

## Dependencies
STORY-168

Carried from the STORY-168 verify (MINOR): Room's default `onCorruption` would still delete `masroof.db` if SQLite reports corruption after the startup `quick_check`. Room becomes the only copy here, so guard it in this story (custom open-helper callback that never deletes the file) with a test.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: repository + wiring ≈ 130, tests ≈ 250)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D3) |
