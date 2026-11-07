# Teardown: "Say - Hands free budget app" (com.moments.expenses)

Date: 2026-11-08. Purpose: decide which of Say's features Masroof should match, and where Masroof can do better.
Masroof copies no code, name, logo, artwork or wording from Say. Only the feature set and screen patterns are reused.

## Evidence and its limits
| source | what it gave | limit |
|---|---|---|
| Google Play listing (en, Egypt store), read 2026-11-08 | description, 12 screenshots, 4.8 stars / 2.46K reviews, 50K+ installs, updated 2026-10-26, "No data collected" label | marketing text and staged screenshots |
| sayapp.net home page | feature list, bank coverage claim, Pro price (500 EGP/year promo, was 700) | marketing text |
| 160 Play reviews (106 five-star, 36 at 1-3 stars) | what users like and what breaks | self-selected reviewers |
| APK binary | **not inspected**: no device with Say installed was attached and no Play-enabled emulator exists; APK mirror sites are untrusted sources | everything below about internals is inferred |

Inferences (not observed): the app is likely React Native/Expo (iPad-first screenshots, the developer mentions an
"over-the-air update" from inside the app) with a server back end and a cloud LLM for "Say AI" (sign-in with Google,
reviewers report it does not work offline). Unknown: SMS parsing method, LLM vendor, data model.

## Feature map (observed in screenshots or listing)
1. **Wallet (home).** Period pill (e.g. 1-30 Sep), hero card "Spent" with today's spend under it, account switcher
   ("All Accounts"), balance and "You owe" (debt) line, quick actions Add / Voice / Pay bill (soon) / More, horizontal
   accounts carousel with per-account balance, recent transactions with merchant logo, category, account and time.
2. **Capture.** Bank SMS, bank and wallet app notifications, Apple Pay (iOS). Onboarding picks your banks from a logo
   grid ("500+ banks"). Claims: transfers between own accounts are not spending; refunds net off the original payment.
3. **Voice entry.** "150 pounds on fuel" in English, Arabic or mixed; a recording sheet with a timer.
4. **Say AI (Ask tab).** Chat that answers money questions with a written answer plus a chart (donut of top
   categories, legend with percentages). Suggested prompts: where did my money go, what's left until payday, how often
   do I order Talabat, this month vs last month, "roast me". Chat history and new-chat buttons.
5. **Wealth tab.** Net worth / Assets / Debt switch; net worth with change this year; line chart with 1M / 3M / 1Y /
   All and a tap tooltip naming what moved it ("Bought 30g, Gold 21k"); stacked bar by asset class (spendable,
   investments, property, vehicles, gold and silver, bank certificates); spendable accounts list with bank logos.
   Gold valued daily by purity; funds that grow at their own rate.
6. **Installments tab.** Monthly total and when it drops ("drops to X in 7 mo"), active count, remaining total,
   "coming up" list, per plan: provider (valU, Halan, Souhoola), purchase price, monthly amount, x of N paid with a
   progress bar, estimated completion, next payment date, down payment, category, reminder, "Record payment",
   "Mark as complete", "Ask about this".
7. **Subscriptions.** Sheet with per-month / per-year toggle, total, active count, list with service icon, cadence,
   amount and next renewal date.
8. **Goals.** Total saved vs total target with a bar; each goal has an emoji ring, saved of target, days to go,
   "Add money" / "Take out", "needs X a month to make it", a step chart of money added; goals can hold gold.
9. **Budgets** per category with an alert before going over; weekly and monthly reports; top places.
10. **Household**: one shared budget, both partners see who spent what (needs an account and sync).
11. **Multiple currencies and accounts** (cash, bank, cards); currency picker with flags.
12. **Export** to one Excel file (Pro only).
13. **Arabic and English**, including mixed speech.

## What users complain about (36 reviews at 1-3 stars, grouped)
| theme | count (approx.) | Masroof position |
|---|---|---|
| SMS tracking misses messages, Apple Pay texts, fails with Truecaller as default SMS app, stops after updates | 9 | on-device rules parser, review list, template learning, 90-day backfill (STORY-186) |
| Paywall on basics (2nd account, SMS, export), 3-day trial, card needed to try | 8 | no paywall, no account |
| Needs internet / sign-in; "save data locally" | 4 | local-only by design (ADR-0009) |
| Crashes, hangs, black screen, slow | 5 | gate: tests, perf budgets |
| Wrong totals, duplicate or missing entries | 3 | golden analytics parity, duplicate linking |
| No past transactions before install | 1 | 90-day SMS backfill |
| No dark mode | 1 | check theme |
| Fixed categories, want custom ones with more emoji | 1 | STORY-196 |
| No Android home-screen widget | 1 | STORY-192 |
| Privacy fear (notification access, vague retention) | 2 | nothing leaves the phone; notification access scoped to bank apps |
| Voice recognition poor, currency missing | 1 | on-device recognizer + rules, any ISO currency |

## Where Masroof can be better
- Everything works offline with no account; no data leaves the phone (Say needs sign-in and a server).
- No paywall.
- Reliability first: every capture is visible (review list) and past SMS can be read in.
- "Ask" answers come from deterministic on-device queries over Room, so the numbers always match Insights (Say's LLM
  answers can disagree with its own charts; one reviewer reports wrong calculations).
- Dark mode, Android widget, custom categories.
