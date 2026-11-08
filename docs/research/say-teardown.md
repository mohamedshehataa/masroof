# Teardown: "Say - Hands free budget app" (com.moments.expenses)

Date: 2026-11-08. Purpose: decide which of Say's features Masroof should match, and where Masroof can do better.
Masroof copies no code, name, logo, artwork or wording from Say. Only the feature set and screen patterns are reused.

## Evidence and its limits
| source | what it gave | limit |
|---|---|---|
| Google Play listing (en, Egypt store), read 2026-11-08 | description, 12 screenshots, 4.8 stars / 2.46K reviews, 50K+ installs, updated 2026-10-26, "No data collected" label | marketing text and staged screenshots |
| sayapp.net home page | feature list, bank coverage claim, Pro price (500 EGP/year promo, was 700) | marketing text |
| 160 Play reviews (106 five-star, 36 at 1-3 stars) | what users like and what breaks | self-selected reviewers |
| APK v1.0.72 (versionCode 178, minSdk 24, targetSdk 36), pulled 2026-11-08 by adb from the owner's phone (SM-S926B), 5 splits | Expo config, manifest, 108,686 strings read from the Hermes v98 bytecode string table (no decompiler, no execution) | strings show what exists, not exact logic |
| The installed app on the owner's phone, browsed read-only by adb (screenshots + accessibility tree, kept out of git) | real screens with real data: Wallet, Analytics, Add, transaction edit, Installments, Wealth, Ask, Settings, account wizard | one account, 8 days of data; nothing was saved or sent |

## Architecture (observed in the APK)
- React Native + Expo (expo-router, Hermes bytecode v98, Reanimated, Skia charts), Sentry, Mixpanel, AppsFlyer,
  RevenueCat / Paddle / Stripe billing, Google and Apple sign-in, Firebase messaging, Expo OTA updates.
- Back end is Supabase (`*.supabase.co`) with server functions `ai-chat`, `expense-extraction` and `import-mapping`,
  plus a Vercel AI gateway. So SMS/voice extraction and "Say AI" run on their server: the app needs the network for
  its core loop, which matches the "doesn't work offline" reviews.
- Android: RECEIVE_SMS + READ_SMS, a NotificationListenerService, RECORD_AUDIO, READ/WRITE_CONTACTS (pick a number
  to pay a bill), SYSTEM_ALERT_WINDOW, AD_ID, battery-optimisation exemption, 13 home-screen widgets (quick add,
  weekly spending, budget buddy, top categories, net worth, gold, bills due, savings goal, overview, household,
  investments, credit-card bill, payday). The Play label says "No data collected" while the APK ships Mixpanel and
  AppsFlyer and requests AD_ID.
- About 180 screens (expo-router files). Tabs: Wallet, Wealth, Installments/Bills, Ask (a Buckets tab for budgets and
  goals exists in code). UI strings exist in English, Arabic and at least six other languages.

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

## Feature map from the APK and the real app (beyond the listing)
14. **Wallet, real layout.** Top bar: profile (opens Settings), period picker, Analytics. Hero: spent, today, account
    picker, balance. Quick actions Add / Voice / Pay bill (soon) / More (add account, manage accounts, arrange tiles).
    Accounts carousel. "Needs an answer" cards (a card number seen in SMS that is not in Say, transfers to sort).
    "Get started" cards. A newsletter card. Recent transactions with an emoji category icon, description, hint,
    category and account. A grid of insight tiles at the bottom (top spending donut, subscriptions due, budget, money
    flow in/out, income kept %, "add tile").
15. **Analytics.** Account and month filter; spent with weekly bars; income; net cash flow; savings rate ("33% of what
    you earned"); monthly budget; drill-downs: money flow, top spending, income breakdown, subscriptions, daily
    average, category change.
16. **Add transaction.** Expense / Income / Transfer, amount with currency, category, account, description, hint,
    date; edit adds "Don't count in totals".
17. **Accounts.** Types: debit card, credit card (limit, statement day, due day, card bill), cash, e-wallet,
    property or investment, gold, silver. Each is linked to SMS or a notification app and to the card/account numbers
    that appear in messages; "N numbers detected in SMS" offers to link unknown numbers; ignored cards; SMS phrases per
    account; "set balance".
18. **Bills tab.** Installments with a provider list (valU, Halan, Souhoola, Gam3iya, Money Fellows, Contact, Aman,
    bank/credit card, Forsa, Sympl, another store, no provider), "what is it for" chips, monthly amount and currency,
    duration by months or end date, first payment date, calendar. Subscriptions with a service catalog, cadence,
    reminders, calendar and analytics. Recurring income (salary by SMS or by date; certificates paying interest with a
    maturity date).
19. **Gam3eya (rotating savings circle).** Monthly share, members, payout month and amount, payments made, payout
    landing in an account.
20. **Wealth, real.** Holdings bought in lots (gold/silver grams and karat, average price per gram, live or manual
    price), certificates, stocks/ETFs/crypto with market prices, money-market funds that grow at a rate, property,
    vehicles, a business; hide from totals; goals can be backed by gold or certificates.
21. **Money hygiene flows.** Refund linking (a refund is matched to the expenses it reverses, split if partial);
    transfer check-ins (was it between your own accounts, to a person, a refund?); "What was this?" for unclear
    money (gold purchase or sale, gam3eya, installment, own transfer); ATM withdrawals moved into a cash wallet or
    counted as spending; merchant rules (merchant -> category, apply to past entries).
22. **Budgets.** One monthly budget, category budgets, category groups, a proposed budget from past months, month-end
    surplus to goals, unbudgeted categories.
23. **Ask, real.** Personalised suggestion chips from current data ("4 visits to Amazon this month"), chat history,
    rename the assistant, roast mode in Egyptian Arabic and English, follow-up chips, answers that can propose writes
    (set a budget) through approval cards, feedback buttons.
24. **Settings.** App lock (fingerprint), SMS auto-tracking keywords, ignored cards, roast toggle, accounts, currency,
    month start day, categories, merchant rules, language, newsletters, household, invite friends, export (Pro),
    import from another app (CSV with server-side column mapping), reset all data, delete account.

## Defects seen in the real app (owner's phone, 2026-11-08)
- Balance and net worth go negative (-95,874 EGP) because accounts start at zero and only expenses are captured; the
  net-worth change then reads "-480.2% this year", which means nothing.
- Wealth -> Debt shows EGP 0 while net worth is -95,874: a missing opening balance is treated as a negative asset.
- Top Spending lists "Transfer" as 21% of spending, although the site says transfers between own accounts don't count.
- Extraction, chat and import need the network (server functions).

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
- Honest balances: an account without a known balance shows "Set balance", never a made-up negative number, and net
  worth shows no percent change across a sign change or from zero.
- Transfers between own accounts never count as spending anywhere (one D2 rule for every screen).
- No analytics, attribution or ad-ID SDKs; nothing to declare on a data-safety label.
