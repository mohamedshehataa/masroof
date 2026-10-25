---
id: STORY-163
epic: EPIC-16
title: "Android Insights → Habits tab with insight cards"
phase: 7
status: DRAFT
size: M
modules: [android]
depends_on: [STORY-109, STORY-153, STORY-154, STORY-155, STORY-156, STORY-157, STORY-158]
reqs: [REQ-AN-134, REQ-AN-135]
implements: ["Owner request 2026-10-25 (behavioral insights)", "PROMPT.md §11 Navigation 4 (Insights tabs, drill-down)", "PROMPT.md §11 Design language and Android-specific (offline-first)"]
risk_tier: low
owner_agent: android-engineer
---

# STORY-163 — Android Insights → Habits tab with insight cards

## Goal
The Android app gets the same "Habits / عادات" Insights tab as the PWA (STORY-161): five behavioral cards for the
household or "me", each tappable into the filtered Transactions list (owner request 2026-10-25; PROMPT §11 Insights).

## Requirements and acceptance criteria
### REQ-AN-134 — Habits tab, scope toggle and drill-down (Compose)
**Given** a signed-in member on the Insights tab **Then** a "Habits" tab (Arabic "عادات") follows the existing tabs
and loads `GET /api/analytics/habits?scope=household`; a segmented button "Household / Me" ("البيت / أنا") switches
scope (no option for the other member). Cards render in response order; highlighted cards get the accent outline and
"Worth a look" / "يستاهل نظرة" (never the error red).
Tapping a READY card navigates to Transactions with the card's `drillDown` filters applied as filter chips plus a
removable "From Habits" chip; unsupported filters are skipped with "Some filters not applied".
**Given** the STORY-154 example response **Then** the weekend card shows "Weekends cost more: 600 EGP a day on Fri and
Sat, against 400 EGP on other days." and a 7-bar Vico mini chart, Saturday first, mirrored in RTL.
Unhappy paths:
- **Given** an unknown `kind` **Then** it is skipped without a crash (generated client tolerates unknown enum values).
- **Given** pull-to-refresh fails **Then** the cached report stays with a snackbar "Couldn't refresh".

### REQ-AN-135 — Card text, numbers, states and offline cache
Strings come from `strings.xml` (en) and `values-ar/strings.xml` with exactly the keys and wording of STORY-153..158
(Arabic household/me forms by scope); `params` are formatted by the shared `Money` formatter: whole EGP (HALF_EVEN),
`1,150 EGP` / `1,150 ج.م` / `١٬١٥٠ ج.م` per language and digit setting; ratios one decimal floored; hours as 12-hour
local times; weekday lists localized; `pluralStringResource` for counts; numbers bidi-isolated in Arabic.
States per card: READY (text + mini visual), INSUFFICIENT_DATA (muted, `*.insufficient` text, "{have} of {need}"),
NOT_APPLICABLE (muted, no tap target). The last report per scope is cached in Room and shown offline with
"Offline — showing {date}".
**Given** Arabic with Arabic-Indic digits and the STORY-156 example **Then** the small-purchases card reads
"الحاجات الصغيرة (٨٥ ج.م أو أقل) بتتجمّع: ٦٤ عملية في آخر ٣ شهور بإجمالي ٣٬١٦٢ ج.م، يعني حوالي ١٢٬٦٠٠ ج.م في السنة."
**Given** `history.activePeriods = 0` **Then** the empty state "Habits show up after about 3 months of entries" /
"العادات بتظهر بعد حوالي ٣ شهور من التسجيل".
Unhappy paths:
- **Given** the API fails with no cache **Then** an error state with Retry.
- **Given** offline with no cache **Then** the offline empty state; drill-down disabled offline.
- **Given** font scale 1.5 **Then** amounts are never truncated (text wraps).

## UX states (UI stories only)
empty · loading (card skeletons) · error (Retry) · offline (Room cache + banner) · RTL (mirrored, chart reversed,
bidi isolates, Arabic forms) · dark (brand tokens). Roborazzi screenshots LTR/RTL × light/dark × font scale 1.0/1.5;
TalkBack content descriptions read the full sentence; 48 dp targets.

## Contract impact
None (consumes `GET /api/analytics/habits`; regenerated Kotlin client).

## Data / migration impact
Room: a cache table for the last Habits report per scope (app-local migration).

## Security / privacy / LLM-safety impact
Only `household` and `me`. Room cache is cleared at logout and covered by the biometric lock when enabled (STORY-129).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3 if needed (low tier).

## Dependencies
STORY-109 (Android Insights shell), STORY-153..158 (cards and texts).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 150 production + ≈ 230 tests; string resources excluded as data)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
