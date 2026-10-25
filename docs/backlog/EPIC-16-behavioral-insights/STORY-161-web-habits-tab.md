---
id: STORY-161
epic: EPIC-16
title: "Web Insights → Habits tab with insight cards"
phase: 7
status: DRAFT
size: M
modules: [web]
depends_on: [STORY-105, STORY-153, STORY-154, STORY-155, STORY-156, STORY-157, STORY-158]
reqs: [REQ-AN-129, REQ-AN-130]
implements: ["Owner request 2026-10-25 (behavioral insights)", "PROMPT.md §11 Navigation 4 (Insights tabs, drill-down)", "PROMPT.md §11 Design language (RTL, digits, empty/loading/error states)"]
risk_tier: low
owner_agent: web-engineer
---

# STORY-161 — Web Insights → Habits tab with insight cards

## Goal
The PWA gets a "Habits / عادات" tab in Insights that shows the five behavioral cards (payday, weekends, late nights,
small purchases, category creep) for the household or for "me", each tappable into the filtered Transactions list
(owner request 2026-10-25; PROMPT §11 Insights).

## Requirements and acceptance criteria
### REQ-AN-129 — Habits tab, scope toggle and drill-down
**Given** a signed-in member on `/insights` **Then** a "Habits" tab (Arabic "عادات") appears after the existing tabs
and opens `/insights/habits?scope=household`, which calls `GET /api/analytics/habits?scope=household`.
A segmented control "Household / Me" ("البيت / أنا") switches `scope` and updates the URL; there is no option for the
other member.
Cards render in the order returned (highlighted first). A highlighted card has an accent border and the label
"Worth a look" / "يستاهل نظرة"; never red (red is reserved for over-budget, §11).
Tapping a READY card with `drillDown` opens `/transactions` with the drill-down filters (date range, categories,
weekdays, hours, `timeKnown`, max amount) applied as URL filters (STORY-108 REQ-UI-055 conventions) and a removable
"From Habits" chip.
**Given** the STORY-154 example response **When** the tab loads **Then** the weekend card shows "Weekends cost more:
600 EGP a day on Fri and Sat, against 400 EGP on other days." and a 7-bar mini chart from `detail.byWeekday`,
Saturday first (mirrored in RTL).
Unhappy paths:
- **Given** a card with an unknown `kind` (a newer backend) **Then** it is not rendered and nothing crashes.
- **Given** a drill-down filter the Transactions list does not support yet (`hourFrom/hourTo`) **Then** the
  supported filters are applied and the chip says "Some filters not applied" / "بعض الفلاتر مش متطبقة".
- **Given** a `422` (bad scope in a hand-edited URL) **Then** the tab resets to `scope=household`.

### REQ-AN-130 — Card text, numbers and states
Text comes from i18n bundles holding exactly the keys and wording of STORY-153..158 (EN and AR, Arabic household/me
forms by scope); `params` are formatted with the shared money helper: whole EGP (HALF_EVEN), `1,150 EGP` /
`1,150 ج.م` / `١٬١٥٠ ج.م` per language and digit setting, ratios with one decimal floored, percentages as integers,
hours as 12-hour local times, weekday lists joined by "and"/"و", ICU plural rules for counts. Numbers inside Arabic
sentences are wrapped in bidi isolates. No user-facing string is hard-coded (ESLint i18n rule).
States per card: `READY` → text + mini visual; `INSUFFICIENT_DATA` → muted card with the `*.insufficient` text and a
progress hint "{have} of {need}"; `NOT_APPLICABLE` → muted card with its message, no drill-down.
**Given** Arabic UI with Arabic-Indic digits and the STORY-156 example **Then** the small-purchases card reads
"الحاجات الصغيرة (٨٥ ج.م أو أقل) بتتجمّع: ٦٤ عملية في آخر ٣ شهور بإجمالي ٣٬١٦٢ ج.م، يعني حوالي ١٢٬٦٠٠ ج.م في السنة."
**Given** a new household (`history.activePeriods = 0`) **Then** an empty state "Habits show up after about 3 months
of entries" / "العادات بتظهر بعد حوالي ٣ شهور من التسجيل" (digit per setting) instead of five muted cards.
Unhappy paths:
- **Given** the request fails (`5xx`, network) **Then** an error state with Retry; other Insights tabs keep working.
- **Given** the PWA is offline **Then** the last cached report shows with an "Offline — showing {date}" banner and
  drill-down disabled; with no cache, the offline empty state.
- **Given** a category name longer than 30 characters **Then** text wraps; no truncation of amounts.

## UX states (UI stories only)
empty (new household) · loading (5 card skeletons) · error (Retry) · offline (cached report + banner) · RTL (mirrored
layout, chart order reversed, bidi-isolated numbers, Arabic household/me forms) · dark (brand tokens, accent border
visible at AA contrast). Playwright screenshots LTR/RTL × light/dark × mobile/desktop; axe: zero serious/critical.

## Contract impact
None (consumes `GET /api/analytics/habits`; regenerated TypeScript client).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Only `household` and `me` scopes exist in the UI. Cached reports live in the PWA's per-user cache and are cleared at
logout.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3 if needed (low tier).

## Dependencies
STORY-105 (Insights tabs shell), STORY-153..158 (card kinds and texts). STORY-108 for the drill-down URL convention
(soft: if not DONE, drill-down uses the STORY-027 filter params directly).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 140 production + ≈ 230 tests; i18n bundle lines excluded as data)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
