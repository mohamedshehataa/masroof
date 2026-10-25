# EPIC-16 — Behavioral spending insights ("Habits")

**Phase:** 7 (Analytics), except STORY-165 (Phase 8, web push). **Source:** owner request 2026-10-25. This extends
PROMPT.md §10, which has no behavioral insights; §10 intro rules (month start day, Africa/Cairo, TRANSFER and
CASH_WITHDRAWAL excluded) and §11 (bilingual, RTL, digit setting, empty/loading/error states) apply.
**Status:** all stories DRAFT, fully specified. They become READY when their dependencies are specified and the
owner schedules the epic (see "Scheduling" below).

## What the household gets

| insight | question it answers | story |
|---|---|---|
| Payday effect | "Do we spend more right after salary?" | STORY-153 (uses the pay cycle from STORY-151) |
| Weekend vs weekday | "Do Fridays and Saturdays cost more than other days?" | STORY-154 |
| Late-night spending | "How much do we buy between 23:00 and 04:00?" | STORY-155 (needs time-of-day from STORY-149) |
| Small purchases that add up | "What do all the small buys come to over a year?" | STORY-156 |
| Category creep | "Which category has gone up month after month?" | STORY-157, with an owner-set inflation figure in STORY-158 |
| Gentle nudges | "By the 12th you usually spend 800 EGP on Dining out; you're at 1,150" | STORY-152 (pace baselines) → STORY-159 (engine + in-app) → STORY-160 (push/WhatsApp) → STORY-165 (web push) |
| Habits screens | the cards, the "usual by now" list and nudge settings in both apps | web STORY-161/162, Android STORY-163/164 |

## Story map (dependency order)

| id | title | modules | risk | size | depends_on (inside and outside the epic) |
|---|---|---|---|---|---|
| STORY-149 | Expenses record whether the time of day is known | backend, contracts | high | M | 020, 022, 044, 045, 053, 056, 077, 086 |
| STORY-150 | Habits endpoint, eligible spend and member scope | backend, contracts | high | M | 013, 023, 025, 090 |
| STORY-151 | Pay-cycle detection from salary income | backend, contracts | high | M | 150 |
| STORY-152 | Personal pace baselines by day of period | backend, contracts | high | M | 150 |
| STORY-153 | Payday-effect card | backend, contracts | high | M | 151 |
| STORY-154 | Weekend-vs-weekday card | backend, contracts | high | M | 150, 098, 012 |
| STORY-155 | Late-night spending card | backend, contracts | high | M | 149, 150, 098, 012 |
| STORY-156 | Small-purchases-add-up card | backend, contracts | high | M | 150, 096 |
| STORY-157 | Category-creep card | backend, contracts | high | M | 150, 094, 099 |
| STORY-158 | Owner-set inflation figure for category creep | backend, contracts | high | S | 157, 012 |
| STORY-159 | Nudge engine and in-app nudge feed | backend, contracts | high | M | 152, 102, 024, 015 |
| STORY-160 | Nudge preferences, quiet hours and push/WhatsApp delivery | backend, contracts | high | M | 159, 071, 077, 078 |
| STORY-161 | Web Insights → Habits tab with insight cards | web | low | M | 105, 153, 154, 155, 156, 157, 158 |
| STORY-162 | Web nudge card, "usual by now" list and nudge settings | web | low | M | 161, 152, 159, 160, 030, 031 |
| STORY-163 | Android Insights → Habits tab with insight cards | android | low | M | 109, 153, 154, 155, 156, 157, 158 |
| STORY-164 | Android nudge notifications, nudge card, "usual by now" list and nudge settings | android | low | M | 163, 152, 159, 160, 072, 039, 040 |
| STORY-165 | Nudges reach the iPhone PWA by web push | backend | high | S | 160, 114, 162 |

Merge order follows the backlog rule contract → backend → clients. Once STORY-150..160 have their contract frozen
by the architect, the web (161/162) and Android (163/164) stories run in parallel worktrees.

## Shared rules (defined once, referenced by every story)

- **Eligible spend** (REQ-AN-103 in STORY-150): the STORY-025 spend set (EXPENSE, plus CASH_WITHDRAWAL only when the
  household counts ATM withdrawals; status CONFIRMED or PENDING_REVIEW; not soft-deleted), valued at the stored
  base-currency amount (EGP piasters, never re-converted from the original currency), net of refunds linked to the
  expense by STORY-090. TRANSFER, INCOME, REFUND rows themselves, IGNORED and POSSIBLE_DUPLICATE never count.
- **Discretionary**: the expense's top-level category has `isEssential = false` and the expense is not recurring
  (`isRecurring = false`). Uncategorised expenses count in totals but are never discretionary.
- **Scope** (REQ-AN-103): `household` (everything) or `me` (the caller's own spending: the full amount of unsplit
  expenses they paid, plus their share of split expenses). There is no way to ask for the other member's habits.
- **Periods**: household periods from `month_start_day` in Africa/Cairo (STORY-025 REQ-AN-020); "active period" =
  at least 5 eligible household expenses. Pay cycles (STORY-151) are a separate, salary-anchored timeline used
  only by the payday card.
- **Time of day**: hour-based logic uses only expenses whose time of day is known (STORY-149); the rest are
  counted and reported as "untimed", never guessed.
- **Money and numbers**: all arithmetic in `long` piasters; ratios and shares in basis points (bps, integer,
  floor); medians defined per story; display rounding is client-side (HALF_EVEN to whole EGP) and follows the
  digit setting: `1,150 EGP`, Arabic UI `1,150 ج.م`, Arabic-Indic setting `١٬١٥٠ ج.م`.
- **No LLM anywhere in this epic.** Every number and every sentence comes from deterministic code and fixed
  bilingual templates. Card text is rendered by the clients from `messageKey` + `params`; nudges sent by push or
  WhatsApp are rendered by the server from the same templates.
- **Tone**: non-judgmental and short. Never "overspent", "too much", "bad", "waste", "مبالغة", "تبذير", "غلط";
  never compares one member with the other; no exclamation marks.

## Privacy between members

Part I has no "private expense" concept: both members see every household entry in the app, and either may
edit it (DECISIONS 2026-10-23). This epic adds a stricter rule for its own outputs, following the owner's request:
- Habits cards and nudges are computed for `household` or `me` only; there is no `memberId` parameter.
- A `me` nudge is delivered only to that member, never to the WhatsApp group, and never names the other member.
- WhatsApp group posts carry household-scope nudges only, and only when both members enabled WhatsApp nudges.
- The household pay-cycle response carries dates, never a salary amount; `me` carries the caller's own median salary.

## Deviations from current external docs (recorded for DECISIONS.md)

- WhatsApp Cloud API: messages a business starts outside the 24-hour customer-service window need a pre-approved
  template (paid per conversation). STORY-160 sends a WhatsApp nudge only inside the window or through a configured
  approved template; otherwise the WhatsApp channel is skipped for that nudge and push/in-app still deliver. The
  architect re-checks Meta's current pricing and template rules at design time.

## Cross-epic follow-ups

- STORY-126 (import, Phase 9): imported rows without a time column are stored with the time of day unknown
  (STORY-149 REQ-AN-100 default rule). A note was added to STORY-126.
- STORY-104 (AI monthly report) may later cite Habits cards; any number it cites stays under REQ-AN-070's
  deterministic check. Not part of this epic.

## Requirements (REQ-AN-100..139; REQ-AN-072..099 left free)

| story | REQs |
|---|---|
| STORY-149 | REQ-AN-100 time-of-day flag per source · REQ-AN-101 edits and backfill |
| STORY-150 | REQ-AN-102 habits envelope · REQ-AN-103 eligible spend, discretionary, scope · REQ-AN-104 history/cold start · REQ-AN-105 performance |
| STORY-151 | REQ-AN-106 salary stream detection · REQ-AN-107 pay cycles, missed salary, fallback, API |
| STORY-152 | REQ-AN-108 pace curve and status · REQ-AN-109 period edges, DST, edits |
| STORY-153 | REQ-AN-110 payday computation · REQ-AN-111 payday text |
| STORY-154 | REQ-AN-112 weekend days setting · REQ-AN-113 weekend card |
| STORY-155 | REQ-AN-114 late-night window setting · REQ-AN-115 late-night card |
| STORY-156 | REQ-AN-116 small-purchase threshold · REQ-AN-117 small-purchases card |
| STORY-157 | REQ-AN-118 creep detection · REQ-AN-119 creep card text |
| STORY-158 | REQ-AN-120 inflation setting · REQ-AN-121 creep vs inflation |
| STORY-159 | REQ-AN-122 daily evaluation · REQ-AN-123 frequency limits · REQ-AN-124 wording and tone · REQ-AN-125 in-app feed |
| STORY-160 | REQ-AN-126 preferences · REQ-AN-127 channel routing and privacy · REQ-AN-128 quiet hours, freshness, failures |
| STORY-161 | REQ-AN-129 web Habits tab · REQ-AN-130 web card text and states |
| STORY-162 | REQ-AN-131 web Home nudge card · REQ-AN-132 web "usual by now" · REQ-AN-133 web settings |
| STORY-163 | REQ-AN-134 Android Habits tab · REQ-AN-135 Android card text, states, cache |
| STORY-164 | REQ-AN-136 Android notification channel · REQ-AN-137 Android Home card and list · REQ-AN-138 Android settings |
| STORY-165 | REQ-AN-139 web push for nudges |

Client REQs use the AN area (not UI/AND) because the owner asked for one AN block for the whole epic.

## Scheduling

These stories are an owner extension beyond Part I. They are placed in Phase 7 because they extend §10 analytics
and depend on Phase 7 stories (098, 099, 102, 104/105/109). The owner may run the Phase 7 gate without them by
setting them DESCOPED or moving them later; that is an owner decision, not a slicing one.
