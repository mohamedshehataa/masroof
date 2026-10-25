---
id: STORY-164
epic: EPIC-16
title: "Android nudge notifications, nudge card, 'usual by now' list and nudge settings"
phase: 7
status: DRAFT
size: M
modules: [android]
depends_on: [STORY-163, STORY-152, STORY-159, STORY-160, STORY-072, STORY-039, STORY-040]
reqs: [REQ-AN-136, REQ-AN-137, REQ-AN-138]
implements: ["Owner request 2026-10-25 (gentle nudges: push channel, opt-out, quiet hours)", "PROMPT.md §11 Android-specific (notification channel per type)", "PROMPT.md §11 Navigation 1 (Home insight card) and 5 (More)"]
risk_tier: low
owner_agent: android-engineer
---

# STORY-164 — Android nudge notifications, nudge card, "usual by now" list and nudge settings

## Goal
On Android, nudges arrive as notifications in their own channel, show on Home, and the member controls them; the
Habits tab gets the "usual by now" list; the admin sets weekend days, late-night hours and the inflation figure
(owner request 2026-10-25; PROMPT §11 Android-specific, Home, More).

## Requirements and acceptance criteria
### REQ-AN-136 — "Habit nudges" notification channel and tap target
**Given** an FCM data message `type = INSIGHT_NUDGE` (STORY-160) **Then** the app posts a notification in a new
channel "Habit nudges" / "تنبيهات العادات" (importance DEFAULT, no sound by default, separate from "Budget alerts"),
with the server-rendered title and body; tapping it opens Home with that nudge card in view and calls
`POST /api/insights/nudges/{id}/seen`.
Unhappy paths:
- **Given** the user disabled the channel in system settings **Then** nothing is posted and the in-app card still
  appears.
- **Given** the nudge was dismissed or expired before the tap **Then** Home opens normally without an error.
- **Given** the device is signed in as another household member than the message's target (stale token after
  account switch) **Then** the message is dropped (payload `memberId` ≠ signed-in member).
- **Given** Android 13+ without `POST_NOTIFICATIONS` granted **Then** the settings screen shows "Notifications are
  off for Masroof" with a button to the system setting.

### REQ-AN-137 — Home nudge card and "usual by now" list
Same behaviour and texts as web REQ-AN-131 and REQ-AN-132: the newest NEW/SEEN nudge as a Home card with Dismiss and
"See habits"; `NUDGE_CREATED` stream events (STORY-039) refresh it; the Habits tab lists pace items (TOTAL first,
"Usual by the {day}: {typical} · This month: {actual}", AHEAD in accent colour, LOW_SPEND collapsed,
INSUFFICIENT_HISTORY as one line). Cached in Room for offline display.
**Given** the STORY-152 example **Then** Dining out shows "Usual by the 12th: 800 EGP · This month: 1,150 EGP".
Unhappy paths:
- **Given** dismiss fails offline **Then** the dismiss is not queued; the card stays with "Couldn't hide — retry".
- **Given** `channels.inApp = false` **Then** no Home card.

### REQ-AN-138 — Nudge and habits settings
More → Notifications → "Habit nudges" edits the caller's `InsightPreferences` (on/off, household nudges, channels
in-app/push/WhatsApp, quiet hours with Material time pickers, message language and digits) via
`PATCH /api/me/insight-preferences`; for the ADMIN a "Household habits" section edits `weekendDays` (1–3),
late-night hours and the inflation figure (rate with one decimal, as-of date, label, Clear) via `PATCH /api/settings`,
with the same help text as web ("Masroof never looks it up").
Unhappy paths:
- **Given** a MEMBER **Then** the household section is read-only; `403` shows "Only the admin can change this".
- **Given** `422` **Then** the field error shows under the named field.
- **Given** offline **Then** the screen is read-only with the offline banner.

## UX states (UI stories only)
empty (no nudges, no history) · loading · error (Retry, inline field errors) · offline (Room cache, read-only
settings) · RTL (mirrored, bidi isolates, Arabic-Indic time pickers per setting) · dark. Roborazzi LTR/RTL ×
light/dark × font 1.0/1.5; TalkBack labels for toggles and pickers; 48 dp targets.

## Contract impact
None (consumes STORY-152/159/160 endpoints, `Settings`, FCM `INSIGHT_NUDGE` payload).

## Data / migration impact
Room: cache for the nudge feed and pace list (app-local migration). New notification channel id.

## Security / privacy / LLM-safety impact
Notification content is server-rendered and shows only the member's own or household numbers; lock-screen visibility
`VISIBILITY_PRIVATE` (amounts hidden on the lock screen). No logging of notification text.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3 if needed (low tier).

## Dependencies
STORY-163 (Habits tab), STORY-152, STORY-159, STORY-160 (APIs), STORY-072 (FCM registration and channels),
STORY-039 (live updates), STORY-040 (More tab).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 160 production + ≈ 230 tests; split boundary if larger: REQ-AN-138 settings as its own story)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
