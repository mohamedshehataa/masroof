---
paths:
  - "backend/**/money/**"
  - "backend/**/analytics/**"
  - "backend/**/fx/**"
  - "backend/**/budget/**"
  - "backend/**/split/**"
  - "backend/**/settlement/**"
  - "backend/**/domain/**"
  - "backend/**/*Money*"
  - "backend/**/*Amount*"
  - "backend/**/*Fx*"
  - "web/src/**/*money*"
  - "web/src/**/*Money*"
  - "web/src/**/*amount*"
  - "web/src/**/*currency*"
  - "android/**/*Money*"
  - "android/**/*Amount*"
  - "android/**/*Currency*"
  - "contracts/openapi.yaml"
---
# Money rule

- Money is `(amountMinor: long, currency: ISO-4217)`. EGP minor unit = piaster. **Never `double`/`float`** for any
  money field, parameter, return type, JSON property or DB column (ArchUnit, detekt custom rule and ESLint rule
  fail the build; semgrep flags `double amount` in the domain).
- Use the `Money` value object (backend domain), `web/src/lib/money.ts` helpers, Android `Money` data class. No raw
  `long` arithmetic on amounts outside those types; no `BigDecimal` without an explicit `RoundingMode.HALF_EVEN`
  and scale, and only as an intermediate (FX, VAT) that is converted back to minor units once.
- Same-currency operations only; mixed currencies throw. Splits use `Money.allocate` (shares sum exactly to the
  total). FX uses the stored `fx_rate` on the row; never re-convert history.
- API: `amountMinor` integer (`int64`) + `currency` string in `contracts/openapi.yaml`; never `number` with
  fraction or decimal strings. DB: `BIGINT` + `CHAR(3)`; `NUMERIC(20,10)` only for FX rates.
- Business dates in `Africa/Cairo`, stored as `timestamptz`; period math via the household `month_start_day`
  helper, never ad hoc. Details and examples: `.claude/skills/money-and-dates/SKILL.md`.
- Every change here needs a jqwik property test (money arithmetic, splits, FX round-trip, period tiling) and
  keeps the `money`/`analytics` packages at >= 95% line / >= 90% branch coverage and >= 80% mutation score.
