---
id: STORY-172
epic: EPIC-17
title: The Needs-review list saves local entries and no queued capture is lost
phase: 4
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-171]
reqs: [REQ-SMS-098, REQ-SMS-099, REQ-SMS-100]
implements: ["ADR-0009 D3 (Migrate: parsed_outbox rows are user data, never dropped)", "PROMPT.md §4.1, §4.3 (direction of credits and transfers)", "docs/ESCALATIONS.md SMS-001 (direction regression guard)"]
owner_agent: android-engineer
---

# STORY-172 - The Needs-review list saves local entries and no queued capture is lost

## Goal
Messages the parser would not record by itself sit in the local "Needs review" list (STORY-067, merged). Saving one now
creates a local entry (and still teaches the format), records still waiting in `parsed_outbox` from the server era
become local entries exactly once, and the incoming-transfer direction that SMS-001 escalated stays fixed on the local
path. ADR-0009 D3 "Migrate".

## Requirements and acceptance criteria
### REQ-SMS-098 - Saving a review item creates a local entry
**Given** a review item from sender `NBE` with the suggestion INCOME 1,000 EGP, last four 7781 (message "تمت اضافة مبلغ
1,000 جنيه الى حسابكم ***7781 تحويل من AHMED ALI", corpus `c-r11x-Z18`) **When** the user taps Save without changes
**Then** one expense is written (`type = INCOME`, `amountMinor = 100000`, `status = CONFIRMED` because a human
confirmed it) with one `expense_source` row (`channel` = the item's channel, `localSourceId` = the message's local id),
the review item is removed (its text deleted), the learned template is stored exactly as today (unchanged suggestion,
not a secret-looking message), and nothing is uploaded.
**Given** the user edits the amount or kind before Save **Then** the expense uses the edited values and nothing is
learned (unchanged rule).
**Given** the user taps Dismiss **Then** the item and its text are deleted and no expense exists.
Unhappy path: **Given** the same message was already recorded (its `localSourceId` exists, e.g. backfill and live
capture) **When** Save is tapped **Then** no second expense is written, the item is removed and "Already recorded" is
shown.
Unhappy path: **Given** a review item older than 30 days **Then** it is not shown and is purged (unchanged rule); a
secret-looking item is shown masked and never learned.
Unhappy path: **Given** an amount edited to `0` or above 1,000,000,000,000 minor units **When** Save is tapped **Then**
the field error is shown and nothing is written.

### REQ-SMS-099 - Captures still queued for upload become local entries once
**Given** `parsed_outbox` rows left by an older version (unsent, failed, and one with `claims > 0` whose upload may have
reached the server) and their `captured_tx` rows **When** the app starts **Then** each row becomes one local expense with
the row's kind, amount, currency, time, last four, merchant label and payment method, plus an `expense_source` row with
the row's `localSourceId` and `channel`; income, incoming transfers and amounts above 50,000 EGP become
`PENDING_REVIEW`; the matching `captured_tx.expenseId` points to the new expense; the `parsed_outbox` row is deleted in
the same transaction; nothing is uploaded. Two queued rows that `ChannelPairing` would pair (SMS + notification, same
amount, 3 s apart) become one expense with two sources.
Unhappy path: **Given** the conversion is interrupted after some rows (process killed in the test) **When** the app starts
again **Then** the rest are converted and no expense is duplicated.
Unhappy path: **Given** a row with `claims > 0` (its upload may have reached the server before the answer was lost)
**When** converted **Then** it is converted like any other row (never dropped, `serverId = null`); if the server holds a
twin, the one-time import (STORY-182) turns it into a merge suggestion instead of keeping two silent copies
(REQ-DEDUP-066).

### REQ-SMS-100 - Incoming transfers are never booked as money out (SMS-001 guard)
**Given** the fixture "تم استقبال تحويل لحظي إلى حسابكم 4471 بمبلغ 1,250.00 جم من OMAR KHALED SAMIR"
(`r4-own-shape-in`) **When** captured through the local path **Then** the stored expense has `type = INCOME`,
`status = PENDING_REVIEW`, `amountMinor = 125000`, last four `4471`, and it is not counted in spending.
**Given** "تم استلام تحويل لحظي بمبلغ 900 جنيه من AHMED ALI الى حسابك ***7781" (`c-r11x-Z26`) **When** captured
**Then** it becomes a review item whose suggestion kind is INCOME (never TRANSFER_OUT) and no expense is written until
the user saves it.
Unhappy path: **Given** every row of `fixtures/sms/*.jsonl` whose expectation is INCOME, TRANSFER_IN or REFUND **When**
run through the local path **Then** none is stored as `EXPENSE` or an outgoing transfer (test iterates the files).

## UX states (UI stories only)
- Review list: unchanged layout; new snackbar "Already recorded" (`values` + `values-ar`).
- loading/empty/error: existing states; a write failure shows "Couldn't save, try again" and keeps the item.
- offline: identical to online. RTL / dark: unchanged components; `FLAG_SECURE` kept on the review screen.

## Contract impact
None.

## Data / migration impact
No schema change. Data migration in code: `parsed_outbox` -> `expense` + `expense_source` (REQ-SMS-099). The table is
dropped only by the v10 contract step (STORY-198).

## Security / privacy / LLM-safety impact
Review text stays only in `review_item`, is deleted on Save/Dismiss and after 30 days, and is never logged. Saved
entries carry typed fields only. No new permission. security-reviewer sign-off with STORY-171 (L1).

## Eval impact
None for the LLM harness. `fixtures/sms/*.jsonl` drive REQ-SMS-100; no fixture expectation changes.

## Design notes (architect)
ADR-0009 D3 "Migrate" (ambiguous matches with imported server rows are handled by the import, D3 last bullet).

## Dependencies
STORY-171

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: review save ≈ 50, conversion ≈ 90, tests ≈ 230)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D3), reuses STORY-067 review list |
