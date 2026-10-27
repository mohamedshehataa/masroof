---
id: STORY-176
epic: EPIC-17
title: The phone computes periods and projections exactly like the server did
phase: 7
status: IN_PROGRESS
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-168]
reqs: [REQ-AN-140, REQ-AN-141, REQ-AN-142]
implements: ["ADR-0009 D2 (AnalyticsPeriod + Projections line-for-line Kotlin port, parity tests 1 and 2)", "PROMPT.md §10 items 1 (period, month start day, projections)"]
owner_agent: android-engineer
---

# STORY-176 - The phone computes periods and projections exactly like the server did

## Goal
Insights and budgets move to the phone (ADR-0009 D2). This slice ports the server's period maths (`AnalyticsPeriod`,
`BusinessCalendar`) and projection maths (`Projections`) to a pure Kotlin package `eg.masroof.core.analytics` and proves
with the server's own test cases plus a randomized check that the results are identical, so the numbers the household
sees do not change when the server goes away. No screen changes here (STORY-178 wires them).

## Requirements and acceptance criteria
### REQ-AN-140 - Periods match the server for every case of its tests
**Given** every case of `backend/src/test/java/eg/masroof/domain/analytics/AnalyticsPeriodTest.java` and
`backend/src/test/java/eg/masroof/domain/expense/BusinessCalendarTest.java` (current/previous, `YYYY-MM`, `YYYY-Www`,
month start day 1..28, ISO weeks, `start()`/`endExclusive()` as UTC instants via `CairoTime`, `elapsedDays`, `daysLeft`)
**When** the same inputs go through the Kotlin port **Then** every output equals the Java expectation; e.g. with month
start day 25 and today 2026-10-27 Cairo, the current period starts 2026-10-25 00:00 Cairo (2026-10-24T21:00:00Z) and
ends exclusive 2026-11-25 00:00 Cairo.
Unhappy path: **Given** month start day 0, 29 or 31, a malformed period `2026-13`, `2026-W54` or `abc` **Then** the port
raises the same error class of outcome as the Java code (invalid argument), never a silent fallback to day 1.
Unhappy path: **Given** the DST transitions in Cairo (last Friday of April 00:00 -> 01:00, last Thursday of October
24:00 -> 23:00) **Then** day starts, `elapsedDays` and period boundaries equal the Java results for both 2026 dates.

### REQ-AN-141 - Projections match the server, including rounding
**Given** every case of `backend/src/test/java/eg/masroof/domain/analytics/ProjectionsTest.java` (`divideRounded` half
away from zero, `deltaBps`, `shareBps`, `savingsRateBps`, `dailyAverage`, `linear`, `weekdayAware`) **When** run through
the Kotlin port **Then** every output equals the Java expectation.
**Given** a seeded randomized test (fixed seed, at least 1,000 cases per function, values up to ±10^15 minor units)
**When** each function is compared with a `BigInteger` reference implementation of the same formula **Then** all cases
are equal.
Unhappy path: **Given** a previous total of 0 for `deltaBps` or income 0 for `savingsRateBps` **Then** "no value" is
returned (as the Java `OptionalInt.empty()`), never a division by zero.

### REQ-AN-142 - Overflow is an error, never a wrong number, and money is never a float
**Given** inputs whose product or sum overflows `Long` (e.g. `linear(Long.MAX_VALUE, 1, 31)`) **When** computed **Then**
an `ArithmeticException` is raised (exact arithmetic as `Math.multiplyExact`/`addExact` in Java), never a wrapped value.
**Given** the package source **Then** it imports nothing from `android.*` or Room and contains no `Double`/`Float`
type (detekt money rule and semgrep `money-no-float` run on it and pass).

## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (pure arithmetic on typed values; nothing logged).

## Eval impact
None for the LLM harness. Parity is gated by the ported unit tests and the randomized `BigInteger` check.

## Design notes (architect)
ADR-0009 D2: line-for-line port into `eg.masroof.core.analytics`, `Long`/`Int` only, exact arithmetic.

## Dependencies
STORY-168 (only for ordering; the package is pure Kotlin)

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: port ≈ 200 lines of Kotlin for ≈ 280 Java, tests ported ≈ 180; generated test tables excluded)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D2) |
