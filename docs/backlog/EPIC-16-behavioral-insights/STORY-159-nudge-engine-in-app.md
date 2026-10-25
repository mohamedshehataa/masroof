---
id: STORY-159
epic: EPIC-16
title: "Nudge engine and in-app nudge feed"
phase: 7
status: DRAFT
size: M
modules: [backend, contracts]
depends_on: [STORY-152, STORY-102, STORY-024, STORY-015]
reqs: [REQ-AN-122, REQ-AN-123, REQ-AN-124, REQ-AN-125]
implements: ["Owner request 2026-10-25 (gentle nudges: 'you usually spend X on delivery by now')", "PROMPT.md §10 item 8 (budget alerts, kept separate)", "PROMPT.md §9 (/api/stream)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-159 — Nudge engine and in-app nudge feed

## Goal
Once a day Masroof checks each member's pace against their own usual pace (STORY-152) and, at most once a day,
creates one calm, factual nudge such as "By the 12th you usually spend about 800 EGP on Dining out. This month you're
at 1,150 EGP." Nudges appear in the app; push and WhatsApp delivery is STORY-160 (owner request 2026-10-25).
Nudges are not budget alerts: they compare with the household's own history, need no budget, and step aside when a
budget alert (STORY-102) just fired for the same category.

## Requirements and acceptance criteria
### REQ-AN-122 — Daily evaluation and candidate rules
A scheduled job runs daily at `nudges.evaluateAt` (config, default 18:00 Africa/Cairo) when the feature flag
`insights.nudges` is on (default off). For each active member it evaluates scope `me` and, when the household has 2
active members, scope `household`, using `GET /api/analytics/habits/pace` logic for the current period.
A pace item is a *candidate* when all hold:
1. `status = AHEAD` (STORY-152 REQ-AN-108);
2. it is the TOTAL item, or its category is discretionary (`isEssential = false`);
3. `dayOfPeriod ≥ 5` and `daysLeft ≥ 3`;
4. no budget alert (STORY-102) fired in the last 24 hours for the same category (for TOTAL: the total budget);
5. the cooldowns of REQ-AN-123 allow it.
Selection: among a member's candidates in both scopes, the one with the largest `actualSoFar − typicalByNow`;
ties → higher `paceBps`, then `me` before `household`, then TOTAL before categories, then category `sortOrder`.
At most one nudge per member per run. A nudge stores a snapshot: `memberId, scope, categoryId|null, periodStart,
dayOfMonth` (Cairo calendar day of the run), `typicalByNow, actualSoFar, daysLeft, messageKey, params, state = NEW,
createdAt`.
**Given** `monthStartDay = 1`, the run on 2026-10-12 18:00 Cairo, and Mohamed's `me` Dining out item
`typicalByNow = 80,000`, `actualSoFar = 115,000`, `AHEAD` (STORY-152 example), and no other candidate **When** the job
runs **Then** one nudge for Mohamed with `scope = ME`, `messageKey = nudge.category.me`, `dayOfMonth = 12`,
`typical = 80,000`, `actual = 115,000`.
**Given** `monthStartDay = 25` and a run on 2026-11-12 **Then** `dayOfMonth = 12` (calendar day, "by the 12th"),
while the pace uses `d = 19`.
Unhappy paths:
- **Given** the flag `insights.nudges` is off **Then** no nudge is created.
- **Given** `dayOfPeriod = 4` or `daysLeft = 2` **Then** no nudge.
- **Given** the item is `INSUFFICIENT_HISTORY`, `LOW_SPEND`, `ON_PACE` or `BEHIND` **Then** no nudge.
- **Given** Bills is `AHEAD` because the electricity bill came early **Then** no nudge (essential category).
- **Given** a Dining out budget alert at 80 % fired at 10:00 the same day **Then** no Dining out nudge that day.
- **Given** a household with one active member **Then** only `me` is evaluated.
- **Given** a member removed by the admin (STORY-015) **Then** no nudge is created for them, and their earlier nudges
  are no longer returned by any endpoint.
- **Given** the database fails while the job processes a member **Then** that member's transaction rolls back (no
  partial nudge), the job continues with the next member, and an error metric is recorded without amounts.

### REQ-AN-123 — Frequency limits and idempotency
- The same (member, scope, category-or-TOTAL) gets at most one nudge per period.
- A member gets at most 1 nudge per Cairo calendar day and at most 3 in any rolling 7 days (all states count).
- Re-running the job on the same day creates nothing new.
**Given** Mohamed got a Dining out `me` nudge on 2026-10-12 **When** Dining out is still AHEAD on 2026-10-13
**Then** no Dining out `me` nudge; another candidate may be chosen if the daily and 7-day limits allow.
**Given** 3 nudges on 2026-10-06, 10-09 and 10-11 **When** the job runs on 2026-10-12 **Then** no nudge; on
2026-10-13 one may be created (the 10-06 nudge left the 7-day window).
Unhappy paths:
- **Given** the job is triggered twice concurrently (two instances) **Then** at most one nudge per member per day
  exists (unique constraint or lock; the loser creates nothing).
- **Given** a new period starts **Then** the per-period limit resets.

### REQ-AN-124 — Nudge wording and tone
Nudges use fixed bilingual templates only (no LLM). `params`: `day` (calendar day; English shows an ordinal "12th",
Arabic shows the number), `category` (CategoryRef), `typical`, `actual` (Money), `daysLeft` (integer, plural rules
apply).

| key | English | Arabic (Egyptian) |
|---|---|---|
| `nudge.category.me` | By the {day} you usually spend about {typical} on {category}. This month you're at {actual}. Just a heads-up. | لحد يوم {day} في الشهر عادةً بتصرف حوالي {typical} على {category}. الشهر ده وصلت {actual}. مجرد تنبيه. |
| `nudge.category.household` | By the {day} the household usually spends about {typical} on {category}. This month it's {actual}. | لحد يوم {day} في الشهر البيت عادةً بيصرف حوالي {typical} على {category}. الشهر ده وصلنا {actual}. |
| `nudge.total.me` | By the {day} you usually spend about {typical} in total. You're at {actual}, with {daysLeft} days to go. | لحد يوم {day} في الشهر عادةً بتصرف حوالي {typical} في المجمل. وصلت {actual} وفاضل {daysLeft} يوم. |
| `nudge.total.household` | By the {day} the household usually spends about {typical} in total. This month it's {actual}, with {daysLeft} days to go. | لحد يوم {day} في الشهر البيت عادةً بيصرف حوالي {typical} في المجمل. الشهر ده وصلنا {actual} وفاضل {daysLeft} يوم. |
| `nudge.title.category` | A quick look at {category} | نظرة سريعة على {category} |
| `nudge.title.total` | A quick look at this month | نظرة سريعة على الشهر |

**Given** the REQ-AN-122 example **When** rendered in English **Then** "By the 12th you usually spend about 800 EGP
on Dining out. This month you're at 1,150 EGP. Just a heads-up."; in Arabic with Western digits
"لحد يوم 12 في الشهر عادةً بتصرف حوالي 800 ج.م على مطاعم. الشهر ده وصلت 1,150 ج.م. مجرد تنبيه." (category shown
with its seeded `nameAr`).
Tone rules, checked by a unit test over every template in both languages:
- none of: overspent, overspending, too much, waste, bad, careful, warning, must, should, مبالغة, تبذير, غلط, لازم,
  خلي بالك, تحذير; no `!`; no literal digits in templates;
- a `me` nudge never contains another member's name or a reference to another member;
- the rendered body is ≤ 160 characters for `typical`/`actual` up to 999,999 EGP and the longest seeded category name.
Unhappy path: **Given** a category renamed after the nudge was created **Then** the feed shows the current name
(CategoryRef resolved at read time), the numbers stay the snapshot.

### REQ-AN-125 — In-app nudge feed
`GET /api/insights/nudges?state=` (default `NEW,SEEN`) → `200 {items: Nudge[]}` with the caller's own nudges of the
current period, newest first, at most 50. `Nudge = {id, scope, category: CategoryRef|null, dayOfMonth, typicalByNow,
actualSoFar, daysLeft, messageKey, titleKey, params, state, createdAt}`.
`POST /api/insights/nudges/{id}/seen` and `POST /api/insights/nudges/{id}/dismiss` → `204`, idempotent.
When a nudge is created, a `StreamEvent {type: NUDGE_CREATED, payload: {nudgeId}}` goes only to the target member's
open `/api/stream` connections.
**Given** Mohamed has 2 NEW nudges **When** he calls the feed **Then** both are returned; **When** he dismisses one
**Then** the default feed returns 1.
Unhappy paths:
- **Given** Member 2 calls `/dismiss` or `/seen` on Mohamed's nudge id **Then** `404` (no existence leak) and the
  nudge is unchanged; Member 2's feed never contains it.
- **Given** an unknown id or a malformed uuid **Then** `404` / `400`.
- **Given** `state=ARCHIVED` **Then** `422`.
- **Given** the period ended **Then** its nudges are no longer in the default feed.
- **Given** Member 2's browser has an open stream **When** Mohamed's nudge is created **Then** Member 2's stream gets
  no `NUDGE_CREATED` event.

## UX states (UI stories only)
N/A (rendered by STORY-162/164).

## Contract impact
New `GET /api/insights/nudges`, `POST /api/insights/nudges/{id}/seen`, `POST /api/insights/nudges/{id}/dismiss`;
schemas `Nudge`, `NudgeList`, `NudgeState` (`NEW`, `SEEN`, `DISMISSED`, plus `EXPIRED` used by STORY-160),
`NudgeScope`; `StreamEvent.type` gains `NUDGE_CREATED` (additive enum value; clients ignore unknown types).

## Data / migration impact
New migration: `nudge` table (uuid PK, household_id, member_id, scope, category_id null, period_start date,
day_of_month, typical_minor/actual_minor BIGINT + currency CHAR(3), days_left, message_key, params jsonb, state,
created_at/updated_at) with a unique key on (member_id, scope, category-or-total, period_start) and an index on
(member_id, created_at).

## Security / privacy / LLM-safety impact
Nudges are per member: feed, actions and stream events are filtered by the caller (IDOR tests with two members of
the same household). Templates only, no LLM. Logs carry nudge ids and member ids, never amounts or category names.

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-152 (pace), STORY-102 (budget alerts to step aside from), STORY-024 (stream), STORY-015 (member removal).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 140 production + ≈ 250 tests; split boundary if larger: REQ-AN-125 feed API as its own story)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
