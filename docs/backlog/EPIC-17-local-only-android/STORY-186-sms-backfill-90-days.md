---
id: STORY-186
epic: EPIC-17
title: Read the last 90 days of bank SMS into entries, with progress and cancel
phase: 4
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-172]
reqs: [REQ-SMS-101, REQ-SMS-102, REQ-SMS-103]
implements: ["PROMPT.md §4.1 (backfill with READ_SMS, default 90 days)", "ADR-0009 D1 §4.1 (backfill stays on the device), D3 (BACKFILL channel, same localSourceId as live capture)"]
owner_agent: android-engineer
---

# STORY-186 - Read the last 90 days of bank SMS into entries, with progress and cancel

## Goal
On a new install the history in the SMS inbox becomes entries: the user picks a range (default 90 days), the app reads
allowlisted senders through `Telephony.Sms.Inbox`, runs the same on-device parser and filters, and writes local entries
or review items, with progress and cancel. Backfill and live capture never record the same SMS twice. Replaces
STORY-068 (descoped; it queued to the upload outbox).

## Requirements and acceptance criteria
### REQ-SMS-101 - Backfill a chosen range
**Given** Settings -> "Read past SMS" (or onboarding, STORY-187), `READ_SMS` granted, range "Last 90 days" (other
choices: 30 days, 6 months, custom start date) **When** the user taps Start **Then** only messages from allowlisted
senders received in the range are read (oldest first), each goes through `SecretDetector`, `StatusGate`, the parser,
the families and learned templates exactly as live capture, auto-recorded ones become entries with an `expense_source`
row (`channel = BACKFILL`), the rest become review items (text kept 30 days from now, masked when secret-looking), OTPs
and promos are dropped; a progress line shows "Read 340 of 1,020 messages - 212 entries, 41 to review"; no "Recorded"
notification is posted per message (one summary notification at the end).
Unhappy path: **Given** an allowlisted message older than the range or from a non-allowlisted sender **Then** it is not
read into anything (the non-allowlisted money-like ones may feed "Suggested sources" as today, without text).
Unhappy path: **Given** 5,000 messages in range **Then** the work runs in batches in WorkManager (not on the UI thread)
and the app stays responsive; memory stays bounded (cursor paging).

### REQ-SMS-102 - Backfill and live capture never duplicate
**Given** an SMS already recorded by live capture **When** backfill reads it **Then** its HMAC local id equals the live one
and nothing new is stored (unique `localSourceId`, REQ-DEDUP-062). **Given** a backfill that was cancelled or killed after
400 messages **When** it is started again **Then** it continues and no entry or review item is duplicated.
**Given** a backfilled SMS and its InstaPay notification are both absent from live capture **Then** only the SMS is
recorded (notifications cannot be backfilled), so one entry exists.
Unhappy path: **Given** a backfilled message whose entry the user deleted **When** backfill runs again within 30 days
**Then** it is not recorded again.

### REQ-SMS-103 - Permission and cancel paths
**Given** `READ_SMS` not granted **When** the user taps "Read past SMS" **Then** an explanation ("Masroof reads bank
messages from the senders you allow, only on this phone") is shown before the system dialog. **Given** the user denies
**Then** "Past messages can't be read without permission" with a button to the app's system settings, and live capture
(`RECEIVE_SMS`) is unaffected.
Unhappy path: **Given** a running backfill **When** the user taps Cancel **Then** it stops within one batch, what was
stored stays, and the line shows "Stopped - 212 entries added".
Unhappy path: **Given** `READ_SMS` is revoked during a backfill **Then** it stops with the permission message and no
crash.

## UX states (UI stories only)
- idle (range picker, Start), running (progress line, Cancel), stopped, done (summary), permission denied.
- empty: "No bank messages in this range".
- offline: works offline.
- RTL / dark: strings in `values` + `values-ar`, counts with the digit setting, 48 dp targets.

## Contract impact
None.

## Data / migration impact
No schema change (`expense_source.channel = BACKFILL`). Manifest adds `READ_SMS` (sideloaded APK; allowed by
`android/CLAUDE.md`).

## Security / privacy / LLM-safety impact
Trust boundary L1: a new dangerous permission `READ_SMS`, used only on user action; only allowlisted senders are read;
OTPs never stored; message text only in `review_item` (30-day purge, not in backup); nothing is logged or uploaded.
security-reviewer sign-off (manifest change, ADR-0009 L1).

## Eval impact
None for the LLM harness. Parser behaviour unchanged; `fixtures/sms/*.jsonl` tests stay green; backfill tests feed fixture
rows through a fake inbox cursor.

## Design notes (architect)
ADR-0009 D1 §4.1 and D3 (`BACKFILL` channel, shared HMAC local id).

## Dependencies
STORY-172

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: reader + worker ≈ 120, UI ≈ 50, tests ≈ 180)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot, replaces STORY-068 |
