---
id: STORY-187
epic: EPIC-17
title: A first-run guide sets up capture, past SMS and the household without any sign-in
phase: 4
status: READY
risk_tier: low
size: M
modules: [android]
depends_on: [STORY-186]
reqs: [REQ-AND-139, REQ-AND-140, REQ-AND-141]
implements: ["PROMPT.md §11 Onboarding (Android), minus sign-in, WhatsApp and server steps (ADR-0009 D1)"]
owner_agent: android-engineer
---

# STORY-187 - A first-run guide sets up capture, past SMS and the household without any sign-in

## Goal
A fresh install walks through language, what Masroof reads and why, SMS and notification permissions, past-SMS range,
the household's second member name and a backup reminder - with no account. Every step can be skipped and resumed.
Replaces STORY-070 (descoped; it began with sign-in and mapped cards through the server).

## Requirements and acceptance criteria
### REQ-AND-139 - Step sequence, skip and resume
**Given** a fresh install **When** the app opens **Then** the guide shows: 1 language (العربية / English), 2 "Everything
stays on this phone" (no account, no server, how backup works), 3 SMS capture (explanation, then `RECEIVE_SMS`), 4 past
SMS range (default 90 days; starts STORY-186's backfill in the background), 5 bank-app notifications (explanation, then
the system notification-access screen), 6 "Who else spends in this household?" (optional name, creates a second member),
7 done -> Home. Each step has Skip; the step reached is remembered, so closing the app resumes there; Settings ->
"Setup guide" reopens it.
Unhappy path: **Given** the guide was finished **When** the app starts **Then** it is not shown again.
Unhappy path: **Given** a phone updated from a signed-in version (data present) **When** it starts **Then** the guide is
not forced; a dismissible "Finish setup" card appears on Home instead.

### REQ-AND-140 - Permission denials are explained, never blocking
**Given** step 3 **When** the user denies `RECEIVE_SMS` **Then** "SMS capture is off - you can turn it on in Settings"
is shown and Continue works; **given** "Don't ask again" **Then** the button opens the app's system settings.
**Given** step 5 **When** the user returns from the notification-access screen without enabling it **Then** the step
shows "Not enabled" and Continue works.
Unhappy path: **Given** step 4 with `READ_SMS` denied **Then** the backfill is not started and the step says why.

### REQ-AND-141 - The second member is a label only
**Given** step 6 **When** the user types `Sara` and continues **Then** a member "Sara" exists next to "Me" and paid-by
choices appear (REQ-EXP-047). **Given** Skip **Then** only "Me" exists.
Unhappy path: **Given** an empty name, only spaces, or more than 40 characters **Then** Continue shows "Enter a name up to
40 characters" (Skip still works); an Arabic name `سارة` is accepted.

## UX states (UI stories only)
- Each step: content, primary button, Skip; loading only on step 4 while the range is confirmed.
- error: denial messages above. empty: n/a. offline: fully offline.
- RTL / dark: the language step switches layout direction at once; illustrations mirror; strings in `values` +
  `values-ar` (Egyptian Arabic); 48 dp targets; font scale 1.5 keeps buttons visible (Roborazzi LTR/RTL x light/dark).

## Contract impact
None.

## Data / migration impact
None (settings flag "onboarding step"; members table rows).

## Security / privacy / LLM-safety impact
Permissions are requested only after an in-app explanation; no new permission beyond STORY-186's `READ_SMS`; nothing
leaves the phone.

## Eval impact
None.

## Design notes (architect)
None needed (UI flow over existing settings and permissions).

## Dependencies
STORY-186

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: screens ≈ 150, tests + screenshots ≈ 200)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot, replaces STORY-070 |
