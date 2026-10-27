---
id: STORY-171
epic: EPIC-17
title: Bank SMS and app notifications become local entries with no upload
phase: 4
status: IN_PROGRESS
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-169]
reqs: [REQ-SMS-095, REQ-SMS-096, REQ-NTF-021, REQ-SMS-097, REQ-AND-135]
implements: ["ADR-0009 D3 (captures write expense + expense_source in one transaction)", "ADR-0009 D1 §4.1/§4.2 (kept except the upload), §7 (on-device pairing)", "PROMPT.md §4.1, §4.2"]
owner_agent: android-engineer
---

# STORY-171 - Bank SMS and app notifications become local entries with no upload

## Goal
Everything the on-device parser records by itself (proven families and learned formats, STORY-065/067/069/046 merged in
PR #44, #63, #65) is written straight into Room as an expense with its source, instead of `parsed_outbox` + upload. The
SMS-vs-notification pairing, the "Recorded ... Undo" notice, the Captured chip and Undo keep working on local rows, the
same message never makes two entries, and capture is controlled by a "capture enabled" setting instead of the server
session. ADR-0009 D3 and D1 (§4.1/§4.2/§7).

## Requirements and acceptance criteria
### REQ-SMS-095 - An auto-recorded message is a local entry at once
**Given** SMS capture is enabled and the allowlisted sender `CIB` delivers "Your card ending 4512 was charged EGP 1.00 at
AMAZON" (a proven family) **When** the intake job runs **Then** in one transaction one `expense` row is written
(`amountMinor = 100`, `EGP`, `type = EXPENSE`, `status = CONFIRMED`, merchant label `AMAZON`, `instrumentLast4 = "4512"`,
`paymentMethod` as parsed, `occurredAtMillis` from the message or the receive time, `timeKnown = 1` only when the message
or receive time gives the time of day) plus one `expense_source` row (`channel = SMS`, `localSourceId` = the existing
HMAC local id, `senderId = "CIB"`, `receivedAtMillis`, `instrumentLast4`), and one `captured_tx` row whose `expenseId` is
the local expense id; the entry is in Transactions and Home without a refresh; the quiet notice "Recorded 1 EGP at
AMAZON - Undo" is shown when notices are on; no row is written to `parsed_outbox` and no HTTP request is made.
**Given** income, an incoming transfer or an amount above 50,000 EGP **When** recorded **Then** the expense has
`status = PENDING_REVIEW` (unchanged rule) and never counts in spending.
Unhappy path: **Given** the database write throws (test fake: `SQLiteFullException`) **When** the intake job runs **Then**
no `expense`, `expense_source` or `captured_tx` row exists, no notice is shown, and the job returns retry; the message
text stays only in the WorkManager input as today and is pruned when the job ends.
Unhappy path: **Given** a message whose text is an OTP ("Your OTP is 889900, do not share it") **When** captured
**Then** nothing is stored at all (existing `SecretDetector` rule, tests stay mandatory).

### REQ-SMS-096 - The same message never makes two entries
**Given** an SMS already recorded **When** the same SMS is processed again (WorkManager re-runs a stopped intake job, or
the same message is delivered twice) **Then** the second `expense_source` insert is refused by the unique
`localSourceId`, no second expense, `captured_tx` row or notice appears.
**Given** an entry recorded from an SMS that the user then undid (REQ-SMS-097) **When** the same SMS (or its already
paired notification twin) is processed again within 30 days **Then** nothing is recorded (fixes STORY-067 verify
finding R3-1: the source row survives the soft delete).
Unhappy path: **Given** two intake jobs for the same message running concurrently (test with two coroutines) **When**
both finish **Then** exactly one expense exists and neither job fails.

### REQ-NTF-021 - One transfer announced by SMS and by notification is one entry with two sources
**Given** notification capture is enabled, the bank SMS "تم استقبال تحويل لحظي إلى حسابكم 4471 بمبلغ 1,250.00 جم من
OMAR KHALED SAMIR" (fixture `r4-own-shape-in`) and the InstaPay notification "You have received 1,250.00 EGP from
omar.k@instapay" received 3 seconds apart (either order) **When** both are captured **Then** one expense exists with two `expense_source` rows (`SMS` and
`PUSH`) and one notice; when the notification came first, the expense takes the SMS's `instrumentLast4` and merchant
label (unless the user already edited the entry, then only the source is added) and the first notice is replaced by one
notice. The existing `ChannelPairing` rules stay: same direction, amount and currency, receive times at most 5 minutes
apart, each record pairs at most once, order independent.
Unhappy path: **Given** the two messages are 5 minutes 1 second apart, or the amounts differ (1,250.00 vs 1,200.00), or both
come from the same channel **When** captured **Then** two separate expenses exist.
Unhappy path: **Given** an SMS, a notification at +3 s and a second notification of an equal transfer at +60 s **When**
captured in any order **Then** two expenses exist (the second notification has no twin left).

### REQ-SMS-097 - Undo and the Captured chip work on local entries
**Given** an entry recorded by capture within the last 7 days **When** the user taps Undo on the notice or on the
Captured chip **Then** the expense is soft deleted (`deletedAtMillis` set, REQ-EXP-046), it leaves every list and total
at once, the notice is cancelled, and for a learned format the dialog "Stop learning this format?" is still offered
(Yes deletes the `learned_template`).
Unhappy path: **Given** the user already deleted or edited the entry **When** Undo is tapped on an old notice **Then** a
deleted entry stays deleted (no error), an edited entry is soft deleted too, and the notice is cancelled.
Unhappy path: **Given** a capture older than 7 days **Then** no chip or Undo is shown (the entry is still deletable from
the edit screen).

### REQ-AND-135 - Capture follows the "capture enabled" setting, not the server session
**Given** a fresh install that never signed in, SMS capture switched on in Settings and `RECEIVE_SMS` granted **When** an
allowlisted bank SMS arrives **Then** it is recorded (REQ-SMS-095). **Given** a signed-in phone **When** the user logs out
**Then** capture keeps working. **Given** the user switches SMS (or notification) capture off **When** a message
arrives **Then** it is ignored: no WorkManager job, no row, no notice.
**Given** any capture **Then** the upload workers (`SMS_UPLOAD_WORK`, `SMS_UPLOAD_RETRY_WORK`, outbox sync) are never
enqueued and no network call is made (assert on WorkManager test driver and a recording fake HTTP client).
Unhappy path: **Given** capture is switched off while an intake job is already running **When** the job reaches its write
transaction **Then** it writes nothing (the setting is re-checked inside the transaction).
Unhappy path: **Given** `RECEIVE_SMS` was revoked in system settings **When** Settings opens **Then** the SMS switch shows
off with "Permission needed" and a button to the app's system settings page.

## UX states (UI stories only)
- Settings capture section: switch on/off, "Permission needed" state with a system-settings button, no "sign in to
  capture" message any more.
- loading/empty/error: unchanged list states; the Captured chip and notice keep their current look.
- offline: identical to online.
- RTL / dark: existing strings; the new "Permission needed" string in `values` + `values-ar`. The Arabic notice keeps
  the institution name as today (known NIT from STORY-067 verify, not in scope).

## Contract impact
None. `/api/ingest/parsed-transactions` is no longer called by the app; the endpoint stays on the frozen server.

## Data / migration impact
No schema change (v9). `parsed_outbox` stops receiving rows; leftover rows are converted by STORY-172.
`captured_tx.expenseId` now holds a local expense id.

## Security / privacy / LLM-safety impact
Trust boundary L1 (SMS/notification -> app): unchanged guards (allowlist, `BROADCAST_SMS`,
`BIND_NOTIFICATION_LISTENER_SERVICE`, `SecretDetector`, `StatusGate`, bounded parser output, 50,000 EGP review
ceiling). Message text is still never stored outside `review_item` and is never logged; `expense_source` holds typed
fields only (sender id, last four, receive time, HMAC local id). The capture gate changes from "signed in" to the
setting, so a logged-out phone now captures: intended by ADR-0009. security-reviewer sign-off required (ADR-0009 L1).
Accepted risk: while an intake job waits or retries, the message text sits in the WorkManager input (app-private database, `allowBackup=false`, pruned by WorkManager about a day after the job finishes, and removed by Erase all data); it is not written anywhere else.

## Eval impact
None for the LLM harness (no LLM). Parser accuracy stays gated by the Kotlin tests over `fixtures/sms/*.jsonl`, which
must stay green unchanged; the capture tests (`CapturedPairingTest`, `CapturedRaceTest`, `CapturedDedupTest`) are
adapted to local rows by the test author with every pairing case kept.

## Design notes (architect)
ADR-0009 D3 (`expense_source` is the dedup link, `captured_tx` keeps Undo/chip role) and D7 (upload workers stop being
scheduled once the local write path ships).

## Dependencies
STORY-169

Carried from STORY-169 security review: no capture may upload under a member other than the one who captured it; STORY-171 must remove capture uploads or enforce this.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: pipeline write path + pairing on local rows ≈ 140, gate/setting ≈ 30, tests adapted/added ≈ 220)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D3), reuses STORY-064..069 capture |
| 2026-10-28 | 5 implement | android-engineer | IN_PROGRESS - local capture write path, pairing on local rows, soft-delete Undo, capture switch + permission state; check-fast android GREEN |
| 2026-10-28 | fix round 1 | android-engineer | review-list Save is local, upload worker no-op and old work cancelled at start, non-EGP waits for review, parser reports timeKnown, Undo always cancels its notice |
