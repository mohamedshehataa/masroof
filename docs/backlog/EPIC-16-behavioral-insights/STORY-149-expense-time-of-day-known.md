---
id: STORY-149
epic: EPIC-16
title: "Expenses record whether the time of day is known"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-020, STORY-022, STORY-044, STORY-045, STORY-053, STORY-056, STORY-077, STORY-086]
reqs: [REQ-AN-100, REQ-AN-101]
implements: ["Owner request 2026-10-25 (behavioral insights: late-night spending)", "PROMPT.md §6 step 3 (extract date, time)", "PROMPT.md §4.3 (date/time from bank SMS)", ".claude/skills/money-and-dates §5 (time_known=false for date-only SMS)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-149 — Expenses record whether the time of day is known

## Goal
Every expense says whether its `occurredAt` time of day is real or only its date is known, so hour-based habits
(late-night spending, STORY-155) never treat a date-only entry as a purchase at midnight or at "now"
(owner request 2026-10-25; PROMPT §6 step 3, §4.3).

Today a date-only SMS is stored at 00:00 Cairo (money-and-dates skill §5), a manual entry back-dated in Quick add
keeps the current clock time, and a receipt without a printed time gets the upload time. All three would show up as
fake late-night or daytime purchases.

## Requirements and acceptance criteria
### REQ-AN-100 — Time-of-day flag is set from the source at creation
Every expense gets a boolean `timeKnown`, returned on `Expense` and accepted as an optional input on `ExpenseCreate`
and `ExpensePatch`. It is set at creation as follows. "Message time" means `receivedAt` (SMS, iOS Shortcut),
`postedAt` (push notification) or the WhatsApp message timestamp.

| source | rule | `timeKnown` |
|---|---|---|
| SMS, PUSH_NOTIFICATION, IOS_SHORTCUT | parsed text has an explicit time (`... on 22/10/26 14:05`) | `true`, `occurredAt` = that time in Cairo |
| same | no time in the text, and no date or a date equal to the Cairo date of the message time | `true`, `occurredAt` = message time |
| same | no time in the text, and a date different from the message's Cairo date (posting date, e.g. `on 20/10` arriving 22/10) | `false`, `occurredAt` = 00:00 Cairo of the parsed date (skill §5) |
| WHATSAPP text or voice | no date or time words (`200 بنزين`, `uber 85`) | `true`, `occurredAt` = message time |
| same | an explicit time (`yesterday 11pm`, `امبارح الساعة ١١ بالليل`) | `true`, that time |
| same | a date or relative day but no time (`امبارح 300 عشا`, `on 20/10 450 groceries`) | `false`, 00:00 Cairo of that date |
| receipt photo, PDF, UPLOAD, InstaPay screenshot | a printed purchase time | `true`, that time |
| same | a printed date only, or nothing | `false`; date = printed date, else the Cairo date of the upload |
| MANUAL (`POST /api/expenses`) | request sends `timeKnown` | the value sent |
| same | `timeKnown` omitted, and `occurredAt` is within 15 minutes (either side) of the server's receive time | `true` (Quick add's "now" default was kept) |
| same | `timeKnown` omitted, otherwise | `false` (back-dated entry, so the clock time is not trusted) |
| any other source, including future imports | no explicit time | `false` |

Merged expenses (STORY-086): the expense takes the `occurredAt` and `timeKnown` of the source that supplies
`occurredAt`. If that source has `timeKnown = false` and a SUPPORTING source has `timeKnown = true` on the same
Cairo date, the expense takes that source's time and `timeKnown = true`.

**Given** the SMS `Your Credit Card ending with 1234 was used for EGP 1,250.00 at CARREFOUR MAADI on 22/10/26 14:05`
received at `2026-10-22T12:06:10Z` **When** it becomes an expense **Then** `occurredAt = 2026-10-22T11:05:00Z`
(14:05 Cairo, UTC+3 in DST) and `timeKnown = true`.
**Given** a WhatsApp text `200 بنزين` with message timestamp `2026-10-23T21:40:00Z` **Then** `occurredAt` is that
instant (00:40 Cairo on 24 Oct) and `timeKnown = true`.
**Given** a WhatsApp text `امبارح 300 عشا` sent on 2026-10-23 Cairo **Then** `occurredAt` = 2026-10-22 00:00 Cairo
and `timeKnown = false`.
**Given** `POST /api/expenses` without `timeKnown` and `occurredAt` 3 days ago at the current clock time **Then**
`201` with `timeKnown = false`; **Given** the same request with `timeKnown: true` **Then** `201` with `timeKnown = true`.
Unhappy paths:
- **Given** `timeKnown: "yes"` or `timeKnown: 1` **Then** `422` with `errors[].field == "timeKnown"`.
- **Given** an SMS whose parsed date differs from the message date by more than 7 days (a backfilled statement line)
  **Then** `timeKnown = false` on the parsed date, and the expense is not dropped.
- **Given** a parsed time that does not exist in Cairo (00:30 on the spring-forward night, 2026-04-24) **Then**
  `occurredAt` is shifted forward per `ZonedDateTime` rules (01:30), `timeKnown = true`, and the raw text is kept.
- **Given** the same SMS delivered twice (same `deviceId`, `smsId`) **Then** one expense with one `timeKnown`
  value (idempotency from STORY-056 is unchanged).
- **Given** LLM extraction fails or returns a schema-invalid `time` (`25:61`) **Then** the entry is stored with
  `timeKnown = false` and the existing repair/`NEEDS_REVIEW` path of STORY-045 is unchanged.

### REQ-AN-101 — Edits keep the flag honest and existing rows are backfilled
**Given** `PATCH /api/expenses/{id}` with a new `occurredAt` and no `timeKnown` **Then** `timeKnown` keeps its
previous value; **Given** `PATCH` with `{occurredAt, timeKnown: true}` **Then** both are stored and an `audit_log`
row records before/after of both fields; **Given** `PATCH {timeKnown: false}` alone **Then** `occurredAt` is
unchanged and only the flag flips.
**Given** expenses created before this story **When** the migration runs **Then** each row gets `timeKnown`:
`true` when its PRIMARY `raw_message` source is SMS, PUSH_NOTIFICATION, IOS_SHORTCUT or WHATSAPP and
`|occurredAt − raw_message.received_at| ≤ 15 min`; `true` when the source is MANUAL and
`|occurredAt − created_at| ≤ 15 min`; `false` for every other row (including rows with no `raw_message`).
Unhappy paths:
- **Given** a soft-deleted expense **Then** it is backfilled too (restore must not leave it null).
- **Given** a household other than the caller's **When** PATCH targets its expense **Then** `404` (scoping
  unchanged) and no flag changes.
- **Given** the migration is run against the previous release's schema snapshot with data **Then** it succeeds,
  no row is left null, and re-running the backfill step is a no-op (migration test, expand step only).

## UX states (UI stories only)
N/A (no UI). Quick add (STORY-028/036) keeps working without change: when the user keeps "now", the 15-minute rule
gives `timeKnown = true`; when they back-date, `false`. Sending `timeKnown: true` when the user explicitly edits the
time field is a client improvement for a later story, not required here.

## Contract impact
`Expense.timeKnown: boolean` (required in responses), optional `timeKnown` on `ExpenseCreate` and `ExpensePatch`.
Non-breaking (new response field, new optional inputs). Architect decides the final name; `timeKnown` matches the
skill's `time_known`.

## Data / migration impact
New migration (expand): `expense.time_known boolean NULL`, backfill per REQ-AN-101 in batches, every write path
sets it. A later contract migration (next release, separate story or the architect's plan) sets `NOT NULL`.
No change to `occurred_at` values of existing rows.

## Security / privacy / LLM-safety impact
Ingestion path change (high tier). No new LLM output is trusted: `timeKnown` is derived from the already
schema-validated `date`/`time` extraction fields; an invalid time falls back to `false`. No message text is logged
(only `expense_id`, `raw_message_id`, the flag).

## Eval impact
`sms` and `whatsapp_text` suites: add at least 6 cases tagged `REQ-AN-100` with the expected flag (2 SMS with an
explicit time, 2 SMS with a posting date only, 2 WhatsApp texts with `امبارح`/`yesterday` and no time). The
existing "Date/time correct (Cairo TZ) ≥ 97%" metric (S10.2) must not regress.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-020, STORY-022 (create/patch), STORY-044 + STORY-056 (SMS/push templates and ingest), STORY-045 (LLM
extraction `date`/`time`), STORY-053 (receipts), STORY-077 (WhatsApp texts), STORY-086 (merge picks `occurredAt`).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 120 production + ≈ 240 tests; split boundary if larger: REQ-AN-101 backfill as its own story)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-25 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request); waits for Phase 7 and its Phase 3–6 dependencies |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
