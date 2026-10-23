---
name: money-and-dates
description: Domain rules for money (EGP minor units, half-even rounding, exact split shares, FX storage and round-trip tolerance) and business dates (Africa/Cairo, DST since 2023, month-start-day periods, timestamptz). Loads automatically when money, analytics, FX, budget or period code is touched in backend, web or android; follow it when writing or reviewing that code.
user-invocable: false
paths:
  - "backend/**/money/**"
  - "backend/**/analytics/**"
  - "backend/**/fx/**"
  - "backend/**/budget/**"
  - "backend/**/split/**"
  - "backend/**/settlement/**"
  - "backend/**/*Money*"
  - "backend/**/*Amount*"
  - "backend/**/*Period*"
  - "backend/**/*Fx*"
  - "web/src/**/*money*"
  - "web/src/**/*Money*"
  - "web/src/**/*currency*"
  - "web/src/**/*period*"
  - "web/src/**/*amount*"
  - "android/**/money/**"
  - "android/**/*Money*"
  - "android/**/*Amount*"
  - "android/**/*Currency*"
  - "android/**/*Period*"
---

# Money and dates in Masroof

## 1. Representation
- A monetary value is `(amount_minor: long, currency: ISO-4217 string)`. EGP minor unit = piaster (قرش),
  100 piasters = 1 EGP. `1,250.50 EGP` is stored as `125050` + `"EGP"`. Never `double`/`float` (ArchUnit,
  detekt and ESLint rules fail the build). `BigDecimal` is allowed only with an explicit scale for intermediate
  arithmetic (FX, VAT), and it is converted back to minor units with an explicit `RoundingMode`.
- Backend type: `Money` value object in the domain (`of(long minor, Currency)`, `plus`, `minus`, `times(BigDecimal, RoundingMode)`,
  `allocate(int[] weights)`, `isNegative`, `compareTo` requires same currency, throws on mismatch). JPA:
  `amount_minor BIGINT NOT NULL`, `currency CHAR(3) NOT NULL`. API (contracts/openapi.yaml): `{"amountMinor": 125050, "currency": "EGP"}`,
  `amountMinor` is `integer`/`int64`, never a decimal string or number with fraction.
- Web: `bigint` or `number` (safe below 2^53 piasters = 90 trillion EGP) wrapped in `Money` helpers in
  `web/src/lib/money.ts`; format at the edge only. Android: Kotlin `Long` in `Money` data class; `java.text`
  formatting with the household locale and digit setting.
- Sign: expenses are positive amounts with `type = EXPENSE`; refunds/income positive with their type. Never
  encode direction in the sign; analytics sums by type.
- Ceilings: amounts above `MAX_AMOUNT_MINOR` (config, default 1,000,000 EGP) are forced to `PENDING_REVIEW` (S10.5).

## 2. Rounding
- Default `RoundingMode.HALF_EVEN` (banker's) for FX conversion, VAT/service computation and percentages.
  Document any deliberate exception next to the call (receipt totals use the printed total; never recompute it).
- Round once, at the end of a computation, never per intermediate step.
- Percent-of-budget and daily average are presentation values: compute from minor units, round at display.

## 3. Splits must sum exactly
`Money.allocate(weights)` (EQUAL = equal weights, CUSTOM = member weights or fixed shares):
1. Share_i = floor(total * w_i / sum(w)) in minor units.
2. Remainder r = total - sum(share_i) piasters (0 <= r < n).
3. Give one extra piaster to the first r members ordered by largest fractional remainder, ties by `member_id`
   ascending (deterministic). Example: 100.00 EGP / 3 -> 3334, 3333, 3333 piasters; 0.05 EGP / 2 -> 3, 2.
4. Invariant (jqwik property): `sum(shares) == total` for any total in [-10^12, 10^12] and 1..10 members; every
   share differs from the ideal by < 1 piaster; allocation is idempotent and order-independent apart from the tie rule.
Settlement balances are sums of shares minus paid amounts per member; "Mohamed owes X" is `max(0, -balance)`.

## 4. FX
- Table `fx_rate(date, base, quote, rate NUMERIC(20,10), source)`; rate = how many `quote` units per 1 `base`
  (`base=USD, quote=EGP, rate=50.1234`). Store the rate used on each expense (`fx_rate` column, same scale) so
  conversions are reproducible; never re-convert historical rows when rates change.
- Convert: `amount_egp_minor = HALF_EVEN(amount_original_minor * rate * 10^(scale_egp - scale_original))`.
  Both EGP and USD have scale 2; KWD/BHD/OMR have 3, JPY 0 — read the scale from `Currency.getDefaultFractionDigits()`.
- Round-trip tolerance (property test): converting X -> Y -> X must land within
  `tolerance = ceil(0.5 * 10^-scale_Y / rate) in minor units of X` + 1 minor unit (half a minor unit of the
  intermediate currency expressed in the origin currency, rounded up, plus one). EGP -> USD -> EGP at 50.0:
  tolerance = ceil(0.005 / 50 * 100) + 1 = 2 piasters. USD -> EGP -> USD at 50.0: tolerance = ceil(0.005 * 50 * 100) + 1 = 26 cents.
- Rate lookup is by the transaction date in Africa/Cairo (not the processing date); missing day -> last known rate
  before it, flag `fx_rate_source = 'carried'`, lower confidence. Rates from the owner-configured source only;
  the LLM never supplies a rate.

## 5. Time zone
- All business logic uses `ZoneId.of("Africa/Cairo")` from the household row (`household.timezone`); never the
  JVM/device default, never `ZoneOffset.ofHours(2)` hard-coded.
- Storage: `timestamptz` (`occurred_at`, `received_at`), always UTC instants. Date-only queries use
  `(occurred_at AT TIME ZONE 'Africa/Cairo')::date`, with an expression index when hot.
- DST: Egypt reinstated DST in 2023 (abolished 2015, briefly used 2014). Current rule (verify current each
  release): starts last Friday of April at 00:00 -> 01:00 (UTC+2 -> UTC+3), ends last Thursday of October at
  00:00 -> 23:00 (back to UTC+2). 2026: 24 Apr - 29 Oct. Consequences: one 23-hour and one 25-hour day; a 00:30
  local time on the fall-back night is ambiguous (`ZonedDateTime.withEarlierOffsetAtOverlap()` for SMS without
  offset); 00:30 on the spring-forward night does not exist (`ZonedDateTime` shifts it forward; keep the raw text).
  Keep JVM/Android tzdata current (`java.time` uses the bundled tzdb; the Android app ships `desugar_jdk_libs`
  or checks `TimeZone.getTimeZone("Africa/Cairo").useDaylightTime()` at startup and logs a DEGRADED signal if the
  DST rule disagrees with the backend).
- Parsing an SMS with a date but no time: use 00:00 Cairo and set `time_known=false`; with no date: `received_at`.
  Receipts carry purchase time; SMS carry charge/post time; dedup tolerates ±48h for that reason.

## 6. Month-start-day periods
`household.month_start_day` (msd) in 1..28 (reject 29-31 at the API; the UI explains "to keep every period well
defined"). Period containing local date d:
```
start = (d.day >= msd) ? date(d.year, d.month, msd) : date(prevMonth(d).year, prevMonth(d).month, msd)
end   = start.plusMonths(1)   (exclusive)
label = the month of (end - 1 day)   // the period "belongs" to the month it ends in, i.e. the salary month
```
Examples (msd = 25): 2026-03-10 -> [2026-02-25, 2026-03-25) labelled "March 2026"; 2026-03-25 -> [2026-03-25, 2026-04-25)
labelled "April 2026"; 2026-01-01 -> [2025-12-25, 2026-01-25) labelled "January 2026". msd = 1 -> calendar months.
"vs last period" compares with `[start.minusMonths(1), start)`. Days elapsed = `ChronoUnit.DAYS.between(start, today) + 1`,
days left = `DAYS.between(today, end) - 1`, daily average = spent / days elapsed, projection = daily average * period length
(weekday-aware variant weights by the household's historical weekday shares). Weekly periods start on Saturday
(Egyptian work week) unless configured. All boundaries are midnight Cairo, computed with `LocalDate` in Cairo, then
converted to UTC instants for the query — never compare `LocalDateTime`s across zones.
Property tests: every date belongs to exactly one period; periods tile the timeline with no gaps/overlaps for
all msd in 1..28 and all dates 2000-2100 (DST days included); `label` is monotonic.

## 7. Formatting (display only)
`1,250 EGP` / `1,250.50 EGP` (Latin) and `١٬٢٥٠ ج.م` / `١٬٢٥٠٫٥٠ ج.م` (Arabic-Indic setting on). Group separator
`,` / `٬` (U+066C), decimal `.` / `٫` (U+066B). Drop `.00` on whole amounts in lists, keep two decimals on details.
Tabular numerals (`font-variant-numeric: tabular-nums`; Compose `FontFeatureSettings "tnum"`). Wrap numbers in
Arabic text with bidi isolates (see the `arabic-text` skill). Never format with `double` conversions.
