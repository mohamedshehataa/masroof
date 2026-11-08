# EPIC-18 - Money life: match "Say" feature for feature, offline and without a paywall

Owner request 2026-11-08 (in chat): reverse engineer the Play app "Say - Hands free budget app" (com.moments.expenses)
and build a better replica. Teardown and evidence: [docs/research/say-teardown.md](../../research/say-teardown.md).
Masroof is already ahead on capture (on-device SMS and notification parsing, review list, receipts, golden-parity
insights, dark mode, RTL). This epic adds Say's remaining features to the Android app, under ADR-0009 (local-only):
nothing calls the network, nothing needs an account, no LLM.

## What "better" means here (acceptance bar for every story)
- Works fully offline with no sign-in; Say needs both.
- No paywall: second account, export, capture and every feature below are free.
- Deterministic numbers: "Ask" answers and Home figures come from the same `core/analytics` rule as Insights
  (ADR-0009 D2), so they never disagree. Say's LLM answers can.
- Nothing is saved from voice or text without the user seeing the pre-filled form and tapping Save.
- Android widget, custom categories with emoji, dark mode (Say reviewers ask for all three).

## Story map
| # | story | risk | size | depends on | Say feature |
|---|---|---|---|---|---|
| 1 | STORY-199 Ask Masroof: questions about your money answered on the phone | high | M | - | Say AI |
| 2 | STORY-200 Say it: voice and quick-text entry, income and currency on Add | high | M | - | Voice entry |
| 3 | STORY-201 Room v10: tables for balances, budgets, bills, goals, assets and lots, debts, merchant rules | high | M | - | (foundation) |
| 4 | STORY-202 Bills: installments (valU, Halan, ...) and gam3eya, schedule, record payment, reminders | high | M | 201 | Installments tab, Gam3eya |
| 5 | STORY-203 Bills: subscriptions and recurring income (payday), totals, renewals, reminders | high | M | 202 | Subscriptions, recurring income |
| 6 | STORY-204 Savings goals with emoji, add/take out, monthly need | high | M | 201 | Goals |
| 7 | STORY-205 Wealth: honest account balances, credit cards, debts, certificates, funds, net worth chart | high | M | 201 | Wealth tab, accounts |
| 7b | STORY-209 Gold and silver by the gram: lots, today's price, goals backed by gold | high | M | 205 | Gold holdings |
| 8 | STORY-206 Wallet home, tabs (Wallet, Wealth, Bills, Ask), insight tiles, "don't count in totals" | low | M | 199, 200, 202..205 | Wallet tab, Analytics |
| 9 | STORY-207 Categories with emoji and merchant rules (apply to past entries) | low | S | 201 | Categories, merchant rules |
| 10 | STORY-208 Needs-an-answer cards: link a refund, sort a transfer, unknown card number, ATM cash | high | M | 205 | Refund, transfer check-in |

Say features already planned in EPIC-17 and kept there: local budgets (STORY-180/181, table created by STORY-201),
budget alerts (STORY-189), CSV export (STORY-185), widget (STORY-192), first-run guide (STORY-187), backfill (STORY-186).
Not copied: household sync and sign-in (ADR-0009 drops them), the LLM chat, Apple Pay (iOS), server price feeds for gold
and stocks (the user types today's gram price; see STORY-205), the newsletter, referrals, the World Cup game, paywall.
STORY-192 (widget) should grow to Say's most useful widgets: quick add by voice, bills due, net worth, payday.

Waves: 199, 200 and 201 run in parallel (no shared files except the nav host and string files). 202, 204, 205 and 207
run in parallel after 201 merges (each reachable from Settings -> "Your money" until 206). Then 203 (after 202), 209
(after 205), and finally 206 and 208, which link every new screen. Specs for 202..208 are
written when their wave starts, from the teardown (feature map items 14-24) and the defects list.

## Shared rules (in addition to EPIC-17's)
- Money is `Long` minor units + ISO-4217 code; percentages and rates are integer basis points; no `Double`/`Float`.
- Dates in `Africa/Cairo` via `CairoTime`; a due date is stored as UTC millis of Cairo noon of that day.
- Each feature keeps its strings in its own resource files (`res/values/strings_<feature>.xml` and
  `res/values-ar/strings_<feature>.xml`) so parallel branches do not conflict on `strings.xml`.
- Only STORY-201 changes the Room schema in this epic; later stories add DAO queries, not tables. The ADR-0009 D3
  contract step moves from v10 to v11.
- Every new table is erased by "Erase all data" and joins the backup file list (STORY-183 must include them).
- Reminders are local notifications from one daily WorkManager job; no exact alarms, no network.
- Copy nothing from Say: no name, logo, icon art or wording. Feature patterns only.
