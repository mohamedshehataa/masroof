---
id: STORY-162
epic: EPIC-16
title: "Web nudge card, 'usual by now' list and nudge settings"
phase: 7
status: DRAFT
size: M
modules: [web]
depends_on: [STORY-161, STORY-152, STORY-159, STORY-160, STORY-030, STORY-031]
reqs: [REQ-AN-131, REQ-AN-132, REQ-AN-133]
implements: ["Owner request 2026-10-25 (gentle nudges, per-member opt-out, quiet hours)", "PROMPT.md §11 Navigation 1 (Home insight card) and 5 (More)", "PROMPT.md §11 Design language"]
risk_tier: low
owner_agent: web-engineer
---

# STORY-162 — Web nudge card, "usual by now" list and nudge settings

## Goal
On the PWA a member sees their latest nudge on Home, can compare "usual by now" with "this month" per category in the
Habits tab, and controls their own nudges; the admin sets the household's weekend days, late-night hours and inflation
figure (owner request 2026-10-25; PROMPT §11 Home and More).

## Requirements and acceptance criteria
### REQ-AN-131 — Home nudge card
**Given** `GET /api/insights/nudges` returns at least one NEW or SEEN nudge **Then** Home shows the newest one as a
card (title from `titleKey`, body from `messageKey`, both from the STORY-159 table) with "Dismiss" ("إخفاء") and
"See habits" ("شوف العادات") actions; showing it calls `POST .../seen` once; Dismiss calls `POST .../dismiss` and
removes the card with an Undo snackbar for 5 s (Undo only hides the snackbar; the nudge stays dismissed — no
un-dismiss API).
**Given** a `NUDGE_CREATED` stream event (STORY-030 live updates) **Then** the feed query is invalidated and the new
card appears without reload.
**Given** `InsightPreferences.channels.inApp = false` **Then** no Home card is shown.
Unhappy paths:
- **Given** no nudges, the `insights.nudges` flag off (empty feed) or a `404` on dismiss (already removed) **Then** no
  card / the card disappears silently.
- **Given** a network error on dismiss **Then** the card stays and "Couldn't hide — retry" shows.

### REQ-AN-132 — "Usual by now" list in the Habits tab
**Given** the Habits tab (STORY-161) **Then** below the cards a section "By now vs usual" / "لحد النهارده مقارنة
بالمعتاد" lists `GET /api/analytics/habits/pace` items for the current scope: TOTAL first, then categories, each with
"Usual by the {day}: {typical}" and "This month: {actual}" and a two-bar comparison; `AHEAD` items show the accent
colour (not red) with "Ahead of usual" / "أعلى من المعتاد"; `BEHIND` shows "Below usual" / "أقل من المعتاد".
**Given** the STORY-152 example **Then** Dining out shows "Usual by the 12th: 800 EGP · This month: 1,150 EGP".
Unhappy paths:
- **Given** every item is `INSUFFICIENT_HISTORY` **Then** one line "Needs 3 full months of history" with `{have}` progress.
- **Given** `LOW_SPEND` items **Then** they are collapsed under "Small categories (n)".

### REQ-AN-133 — Nudge and habits settings
**Given** More → Notifications → "Habit nudges" (`/more/nudges`) **Then** the member edits their own
`InsightPreferences`: nudges on/off, household nudges on/off, channels (in-app; push — on the PWA the toggle is enabled
only when this browser has a web-push subscription (STORY-114), otherwise it is disabled with "Allow notifications on
this device first" / "فعّل الإشعارات على الجهاز ده الأول"; WhatsApp), quiet hours (two time pickers, Cairo time
noted), language and digits for messages; Save → `PATCH /api/me/insight-preferences`.
**Given** an ADMIN **Then** a "Household habits" section also edits `weekendDays` (7 toggles, 1–3 allowed),
late-night start/end hours and the inflation figure (rate with one decimal → `annualRateBps`, as-of date, label, and
"Clear") via `PATCH /api/settings`; the inflation field shows the help text "Enter the figure yourself, e.g. the
latest CPI rate. Masroof never looks it up." / "اكتب الرقم بنفسك، مثلاً آخر معدل تضخم. مصروف مش بيدوّر عليه.".
Unhappy paths:
- **Given** a MEMBER **Then** the household section is read-only; a `403` shows "Only the admin can change this".
- **Given** `422` **Then** the error renders beside the named field (e.g. quiet hours equal, 4 weekend days, rate > 100).
- **Given** offline **Then** the form is read-only with the offline banner (no queued settings changes).

## UX states (UI stories only)
empty (no nudges: no Home card; no pace history: single line) · loading (card/list skeletons) · error (Retry; inline
field errors) · offline (read-only settings, cached list) · RTL (mirrored bars and toggles, bidi-isolated numbers,
time pickers in Arabic digits per setting) · dark. Screenshot matrix LTR/RTL × light/dark × mobile/desktop; axe clean;
toggles keyboard-operable.

## Contract impact
None (consumes STORY-152/159/160 endpoints and `Settings`).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Preferences are the caller's own; the admin-only household section relies on server `403`. No other member's nudges
are ever requested.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3 if needed (low tier).

## Dependencies
STORY-161 (Habits tab), STORY-152 (pace), STORY-159 (feed), STORY-160 (preferences), STORY-030 (live updates),
STORY-031 (More tab).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 150 production + ≈ 240 tests; split boundary if larger: REQ-AN-133 settings as its own story)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
