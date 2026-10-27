---
id: STORY-160
epic: EPIC-16
title: "Nudge preferences, quiet hours and push/WhatsApp delivery"
phase: 7
status: DESCOPED
size: M
modules: [backend, contracts]
depends_on: [STORY-159, STORY-071, STORY-077, STORY-078]
reqs: [REQ-AN-126, REQ-AN-127, REQ-AN-128]
implements: ["Owner request 2026-10-25 (gentle nudges: channels push/WhatsApp/in-app, quiet hours, per-member opt-out, privacy between members)", "PROMPT.md §3.2 (direct mode)", "PROMPT.md §6 step 7 (notify)", "PROMPT.md §11 Android-specific (channel per type)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-160 — Nudge preferences, quiet hours and push/WhatsApp delivery

## Goal
Each member decides whether they get nudges, on which channels and outside which quiet hours; nudges created by
STORY-159 then reach them by Android push (FCM) and WhatsApp, rendered in their language and digit style, without ever
exposing one member's own spending to the other (owner request 2026-10-25; PROMPT §3.2, §6 step 7).

## Requirements and acceptance criteria
### REQ-AN-126 — Per-member insight preferences
`GET /api/me/insight-preferences` and `PATCH /api/me/insight-preferences` act on the caller only.
`InsightPreferences = {nudgesEnabled: bool (default true), householdNudges: bool (default true),
channels: {inApp: bool (default true), push: bool (default true), whatsapp: bool (default false)},
quietHours: {start: "HH:mm", end: "HH:mm"} (default 22:00–09:00 Africa/Cairo), language: ar|en, digits: WESTERN|ARABIC_INDIC (default WESTERN)}`.
`language` defaults to the language of the member's latest WhatsApp message, else `ar`.
**Given** Member 2 **When** `PATCH {"channels": {"whatsapp": true}, "quietHours": {"start": "23:00", "end": "08:30"}}`
**Then** `200` with the merged preferences and an `audit_log` row.
**Given** `PATCH {"nudgesEnabled": false}` **Then** STORY-159's job creates no nudges for that member, and their
NEW nudges not yet delivered are not delivered on any channel.
**Given** `householdNudges = false` **Then** the job evaluates only scope `me` for that member.
Unhappy paths:
- **Given** `quietHours.start = end`, `"25:00"`, `"9am"`, `language: "fr"` or `digits: "ROMAN"` **Then** `422` naming the field.
- **Given** a request that names another member (`?memberId=` or a body `memberId`) **Then** `422`; there is no way
  to read or change the other member's preferences, including for the admin.
- **Given** no token **Then** `401`.

### REQ-AN-127 — Channel routing and privacy between members
For each new nudge (state NEW) the delivery worker sends it on each enabled channel of its member, once per
(nudge, channel), and records a delivery row with status `SENT | SKIPPED_<reason> | FAILED`.
- **In-app**: always available through STORY-159's feed (`channels.inApp = false` only hides the Home card in the
  clients).
- **Push**: through STORY-071's FCM sender to the member's own Android devices, notification type `INSIGHT_NUDGE`,
  title from `titleKey`, body from `messageKey`, data `{nudgeId, memberId}`; no merchant names, no other member's name.
- **WhatsApp**:
  - DIRECT mode (STORY-078): a 1:1 message to the member's own number.
  - GROUP mode: only `household`-scope nudges, and only when every active member has `channels.whatsapp = true`;
    `me`-scope nudges are never posted to the group (`SKIPPED_GROUP_PRIVACY`).
  - Meta only lets a business start a conversation outside the 24-hour customer-service window with a pre-approved
    template. If the member (or the group) has not messaged the bot in the last 24 hours and no approved template is
    configured (`WHATSAPP_NUDGE_TEMPLATE`), the WhatsApp channel is skipped (`SKIPPED_WINDOW`) and the other channels
    still deliver. The architect re-checks the current Meta rules at design time (epic README "Deviations").
- Rendering for push and WhatsApp uses the canonical server-side templates (STORY-159 REQ-AN-124) with the member's
  `language` and `digits`: `1,150 EGP` (en), `1,150 ج.م` (ar, WESTERN), `١٬١٥٠ ج.م` (ar, ARABIC_INDIC); amounts
  rounded HALF_EVEN to whole EGP.
**Given** Mohamed's `me` Dining out nudge, push and WhatsApp enabled, DIRECT mode, a WhatsApp message from him 3 hours
ago **When** delivery runs outside quiet hours **Then** one FCM message to his devices and one WhatsApp message to his
number, both with the English text of STORY-159's example, and two `SENT` delivery rows.
**Given** GROUP mode and a `household` nudge with both members' WhatsApp enabled and the group active in the last 24 h
**Then** one group message `لحد يوم 12 في الشهر البيت عادةً بيصرف ...` in the group's language (the language of the
latest group message), and no second copy per member.
Unhappy paths:
- **Given** GROUP mode and a `me` nudge **Then** WhatsApp is `SKIPPED_GROUP_PRIVACY`; push still sends.
- **Given** GROUP mode, a `household` nudge and Member 2 has `channels.whatsapp = false` **Then** WhatsApp is
  `SKIPPED_GROUP_PRIVACY`.
- **Given** a member with no Android device (iPhone/PWA) **Then** push is `SKIPPED_NO_DEVICE` until STORY-165.
- **Given** the delivery worker retries after a crash **Then** no channel sends twice for the same nudge.

### REQ-AN-128 — Quiet hours, freshness and failures
- A nudge whose delivery time falls inside the member's quiet hours (Cairo wall clock; a window may wrap midnight)
  waits until the quiet hours end.
- Just before sending (immediate or deferred), the worker recomputes the pace item; if it is no longer `AHEAD`, or the
  period has ended, the nudge becomes `EXPIRED` and nothing is sent.
- FCM or WhatsApp errors (timeout, 5xx, 429) are retried at most 3 times with backoff within 1 hour, then that channel
  is `FAILED`; other channels are unaffected. An invalid FCM token is handled as in STORY-071 (token removed).
**Given** quiet hours 22:00–09:00 and a nudge created at 18:00 with delivery delayed until 22:15 by a backlog **Then**
it is sent at 09:00 the next day if the item is still AHEAD.
**Given** a deferred Dining out nudge and Mohamed deletes a 400 EGP Dining out expense overnight so the item becomes
ON_PACE **Then** at 09:00 the nudge is `EXPIRED`, nothing is sent and the in-app feed no longer shows it.
Unhappy paths:
- **Given** WhatsApp returns `500` four times **Then** WhatsApp is `FAILED` after 3 retries, push is `SENT`, and the
  nudge stays in the feed.
- **Given** the member turns `nudgesEnabled` off while a nudge is deferred **Then** it is never sent.
- **Given** the DST change night (2026-10-29) **Then** quiet-hour boundaries follow Cairo wall-clock time from the zone
  rules (no fixed offset).
- Logs and metrics carry nudge id, member id, channel and status only — never the rendered text, amounts, phone
  numbers or tokens.

## UX states (UI stories only)
N/A (preferences screens are STORY-162/164; the Android channel is STORY-164).

## Contract impact
New `GET/PATCH /api/me/insight-preferences`, schemas `InsightPreferences`, `InsightPreferencesPatch`; the FCM data
type `INSIGHT_NUDGE` documented with STORY-071's payloads; `NudgeState.EXPIRED` used. Additive.

## Data / migration impact
New migration: `member_insight_preferences` (one row per member, defaults above) and `nudge_delivery` (nudge_id,
channel, status, attempts, last_error_code, sent_at; unique on (nudge_id, channel)).

## Security / privacy / LLM-safety impact
Outbound messages to real phones (WhatsApp, FCM): first real messages are an S16 human checkpoint already covered
by STORY-078. Privacy rules above are test-enforced with two members. Content is template-rendered, no LLM. WhatsApp
template id and FCM credentials come from `.env` (`TODO(owner)` where missing).

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3. Re-check Meta's 24-hour window, template category (utility) and pricing; record the result in
DECISIONS.

## Dependencies
STORY-159 (nudges), STORY-071 (FCM sender), STORY-077 (WhatsApp send + language detection), STORY-078 (group/direct
mode).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 140 production + ≈ 250 tests; split boundary if larger: REQ-AN-126 preferences API as its own story)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
