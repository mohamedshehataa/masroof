# Analytics parity file (version 1)

`parity-v1.json` pins what the server `AnalyticsService` computes for invented seed rows (ADR-0009 D2, parity test 3).
It never changes; a behaviour change is a new `parity-v2.json`. The producer and judge is the backend integration test
`AnalyticsParityIT` (regenerate `scenarios` only with `MASROOF_PARITY_GENERATE=1`). The Android engine
(`eg.masroof.core.analytics`) must reproduce `scenarios` from `expenses`, `categories` and `cases`.

All amounts are integer minor units (piasters, EGP). Ratios (`shareBps`, `deltaBps`, `savingsRateBps`) are integers
(basis points); `null` means "no value" (previous total zero, or no income). Array order is part of the expectation.

| key | meaning |
|---|---|
| `categories[]` | `key`, `parentKey` (null for top level), `nameEn`, `nameAr`, `color` (a child has its parent's colour) |
| `expenses[]` | `id`, `type` (EXPENSE, INCOME, REFUND, TRANSFER, CASH_WITHDRAWAL), `amountMinor`, `currency`, `occurredAt` (UTC instant), `categoryKey` (nullable), `merchant` (nullable, raw), `status` (CONFIRMED, PENDING_REVIEW, POSSIBLE_DUPLICATE, IGNORED), `deleted` (soft-deleted) |
| `cases[]` | `monthStartDay` and the `todays` (Cairo dates) to evaluate it at; the clock is that day at 12:00 Cairo |
| `scenarios[]` | one per (`monthStartDay`, `today`), in file order: `{monthStartDay, today, outputs}` |

`outputs` keys, all for the `current` period: `summary`, `summaryWithCash`, `forecast`, `byCategory`,
`byCategoryWithCash`, `merchants` (limit 50, never with cash), `heatmap`, `heatmapWithCash`, `trend6`, `trend6WithCash`,
`trend12`, `trend12WithCash`. `WithCash` means `includeCashWithdrawals=true`. Field names follow the
`contracts/openapi.yaml` analytics schemas, with money flattened to `amountMinor` numbers and categories to
`categoryKey` plus `nameEn`, `nameAr`, `color`.

- `period`: `{label, from, to}` (inclusive Cairo dates).
- `summary`: `period, total, previousTotal, deltaBps, dailyAverage, projectedTotal, projectedWeekdayAware, income,
  topCategories[] (at most 5), daysLeft, pendingReviewCount`.
- category line: `categoryKey, nameEn, nameAr, color, total, previousTotal, shareBps, count, deltaBps`
  (`categoryKey` null = "Uncategorised / غير مصنف").
- `forecast`: `period, spentSoFar, linear, weekdayAware`. `byCategory`: `period, total, items[]`.
- `merchants`: `period, items[] {name, total, visits, averageTicket, categoryKey}`.
- `heatmap`: `period, days[] {date, total, count}` (every day of the period), `weekdayHour` (7 rows, index 0 = Saturday,
  by 24 Cairo hours).
- `trend6`/`trend12`: oldest first, each `{period, total, income, savingsRateBps, byCategory[]}`.

Top-level keys: `_readme`, `version` (1), `baseCurrency` ("EGP"), `categories`, `cases`, `expenses`, `scenarios`.

## What the Kotlin port must match (server behaviour pinned by this file)
- Totals cover the whole period, including rows dated after `today`: `total`, `previousTotal`, `spentSoFar` (forecast),
  `linear`, trend totals and `pendingReviewCount`. `today` only drives elapsed and left days and the projections.
- A category whose net is negative or zero after refunds is left out of the lines, but it still counts in `total`.
  `shareBps` divides by the sum of the positive categories only.
- Category lines: total descending, then `nameEn` ascending ("Uncategorised" sorts under that name). Ties exist
  (Bills and Transport at 9000).
- Merchants: grouped by `lower(trim(name))`; blank or missing names are skipped; only a positive net is kept; never
  includes cash. Order is total descending, then name ascending; ties exist (Alpha Kiosk, Metro Ride, Water Co at 9000).
- Merchant display name is the smallest trimmed raw name in byte (ordinal) order, which is the collation of the pinned
  Alpine/musl Postgres image: `Carrefour` wins over `carrefour`. `averageTicket` is the half-away-from-zero rounded
  `total / visits`.
- Merchant category is the most frequent non-null category of the group (SQL `mode()` ignores NULLs): `Split Shop` has
  two uncategorised rows and one groceries row and reports `groceries`. A category tie is not seeded (the server picks
  by random id), so the port may break it any way.
- `pendingReviewCount` counts every live `PENDING_REVIEW` row of the period whatever its type, currency or the cash
  flag (it includes a USD row and a transfer).
- Income is the sum of live EGP `INCOME` rows with status CONFIRMED or PENDING_REVIEW; the cash flag does not change it.
- Days and hours are Cairo local time. The Cairo DST changes are pinned: 24 Apr 2026 (midnight is skipped, so
  22:00Z is 01:00) and 29 Oct 2026 (21:00Z is the repeated 23:00 hour of the 29th; 22:00Z is midnight of the 30th).
