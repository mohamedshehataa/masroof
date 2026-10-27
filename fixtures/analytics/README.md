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
