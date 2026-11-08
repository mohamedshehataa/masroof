---
id: STORY-199
epic: EPIC-18
title: Ask Masroof - questions about your money answered on the phone, with a chart
phase: 7
status: DONE
risk_tier: high
size: M
modules: [android]
depends_on: []
reqs: [REQ-AN-160, REQ-AN-161, REQ-AN-162, REQ-AN-163, REQ-AN-164, REQ-AN-165]
implements: ["Say teardown feature 4 (Say AI), done deterministically on the device", "ADR-0009 D1 (no LLM, no network) and D2 (one spending rule)"]
owner_agent: android-engineer
---

# STORY-199 - Ask Masroof - questions about your money answered on the phone, with a chart

## Goal
Mohamed types or taps a question in English, Egyptian Arabic or a mix ("how much did I spend on groceries this month",
"صرفت كام على الأكل الشهر ده", "where did my money go") and gets a short answer, the exact number Insights would show, a
small chart, and the entries behind it. No LLM and no network: a bilingual rules parser turns the question into a
typed query over Room, answered by `core/analytics`.

## Requirements and acceptance criteria
### REQ-AN-160 - Spend total for a period, optionally filtered by category, merchant or account
**Given** October 2026 entries (month start day 1) with Groceries 4,020 EGP and a soft-deleted 500 EGP Groceries entry
**When** the user asks "how much did I spend on groceries this month" or "صرفت كام على البقالة الشهر ده" or
"groceries october" **Then** the answer is "4,020 EGP on Groceries this month" (Arabic in Arabic, digits per the digit
setting) and the figure equals the Insights category amount for the same period (same D2 rule: EGP only, refunds
netted, transfers never, ATM withdrawals per the setting). The entries behind it are listed and tap through to Edit.
Periods understood: today, yesterday, this/last week, this/last month, this year, last N days, a month name or number
("september", "سبتمبر", "شهر ٩"), with the month start day honoured. Category matching uses English and Arabic names,
sub-categories roll up into their parent, and a small synonym table (food -> Groceries + Dining out, أكل, fuel ->
Fuel, بنزين, rent -> Rent & housing, ...). A merchant name ("talabat", "طلبات") matches normalised merchant labels.
Unhappy path: **Given** a category name that matches nothing **When** asked **Then** the answer says it does not know
that category and offers the 3 closest category chips; no number is shown.
Unhappy path: **Given** non-EGP entries in the period **Then** the answer adds "N entries in other currencies not
included" exactly like Insights.

### REQ-AN-161 - Where did my money go: category breakdown with a donut
**Given** the same data **When** the user asks "where did my money go this month" / "فلوسي راحت فين" **Then** the answer
shows total spent, a donut of the top categories (top 5 + "Other") with percentages that sum to 100 (largest
remainder, integer basis points), and a ranked list; tapping a category asks the REQ-AN-160 question for it.
Unhappy path: **Given** no spending in the period **Then** "No spending recorded this month" and no chart.

### REQ-AN-162 - Compare a period with the previous one
**Given** September and October data **When** the user asks "this month vs last month" / "الشهر ده مقارنة باللي فات"
**Then** both totals, the difference and percent change (integer basis points, half away from zero), and the three
categories that changed most, with a two-bar chart. "This week vs last week" works the same way.
Unhappy path: **Given** an empty previous period **Then** no percent is shown ("no spending last month to compare").

### REQ-AN-163 - Merchants: how often and top places
**Given** 14 Talabat entries this month **When** the user asks "how often do I order talabat" / "طلبت من طلبات كام مرة"
**Then** "14 times this month, 3,210 EGP, average 229 EGP" (average = total / count, half away from zero). **When**
the user asks "top places" / "أكتر أماكن بصرف فيها" **Then** the top 5 merchants with totals and visit counts, same
numbers as the Insights merchants card.
Unhappy path: **Given** a merchant with no entries in the period **Then** "No entries for talabat this month".

### REQ-AN-164 - What's left until payday and the biggest expense
**Given** a month start day of 25, today 2026-10-20 Cairo, spent 9,000 EGP since 2026-09-25 and income 20,000 EGP in
the same period **When** the user asks "what's left until payday" / "فاضل كام لحد القبض" **Then** "5 days to payday;
income 20,000, spent 9,000, 11,000 left; at your pace you will spend about 10,500 by payday" (projection from the
existing `Projections` code). Without income in the period it shows days left, spent so far and the daily average.
**When** the user asks "biggest expense this month" **Then** the single largest spending entry with its date and merchant.
Unhappy path: **Given** no entries at all **Then** a friendly empty answer, never a zero-division or a crash.

### REQ-AN-165 - Unknown questions, suggestions, a light "roast", privacy
**Given** the Ask screen **When** it opens **Then** it greets with one fact from the data ("4 visits to Amazon this
month, 16,460 EGP" - the top merchant by total this period) and suggestion chips built from the data (how often do I go
to <top merchant>, why is <top category> so high, this month vs last month, where did my money go, what's left until
payday, roast me) in the app language. Every answer ends with 2-3 follow-up chips (e.g. after a category total: "show
these entries", "compare with last month"). **When** a question is not understood
**Then** the answer says so and shows the chips; it never guesses a number. **When** the user asks "roast me" /
"اتريق عليا" **Then** 2-3 playful lines built only from their own figures (most frequent merchant, biggest category,
late-night count), polite, no insults about the person. The conversation lives in memory only (cleared when the screen
closes); questions and answers are not logged above DEBUG and nothing calls the network (unit test asserts no network
client is reachable from the Ask package).

## UX states (UI stories only)
Route `ask` reachable from the Home top bar and the Insights top bar (STORY-206 later moves it to the bottom bar).
Chat list (question bubbles right in LTR / left in RTL, answer cards with number, chart, entry list), input field with
send, suggestion chips, empty state with chips, not-understood state, long-answer scrolling. TalkBack reads the answer
text before the chart. RTL and dark mode; Arabic-Indic digits when chosen.

## Contract impact
None.

## Data / migration impact
None (read-only queries; add DAO queries if needed, no new tables).

## Security / privacy / LLM-safety impact
No LLM, no network. Question text never leaves the screen's memory; merchant labels are already digit-free.

## Eval impact
None for the LLM harness. A table-driven unit test of at least 60 questions (EN, AR, mixed, Arabic-Indic digits) maps
each to its expected typed query.

## Design notes (architect)
`ask/QuestionParser` (pure Kotlin: normalise Arabic letters - alef/ya/ta marbuta forms, strip tashkeel, Arabic-Indic
digits - then match intent, period, filters) -> `AskQuery` sealed type -> `AskEngine` reusing `core/analytics`
(`AnalyticsCalculator`, `AnalyticsExtras`, `Projections`) and DAO range queries -> `AskAnswer` (text parts + chart spec
+ entry ids) -> Compose screen reusing `ui/insights/Charts.kt` (`CategoryDonut`, `BarChart`).

## Dependencies
None (STORY-178/179 analytics are on main).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size: M (parser ~250, engine ~200, screen ~250, tests ~400)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-11-08 | 1 spec | orchestrator | READY - EPIC-18 (Say teardown), speed mode |
| 2026-11-08 | 5 implement | android-engineer | READY_FOR_VERIFY 09088e0; fix rounds cdb3bf8, fff9a57, ebc3a86, 82933ac, ca36cf0 |
| 2026-11-08 | 6 verify | qa-verifier | FAIL loops 1-5 (each fixed), PASS loop 6 (792 tests; parity exact) - past the 3-loop limit by owner rule, see DECISIONS |
| 2026-11-08 | 8 integrate | orchestrator | DONE |
