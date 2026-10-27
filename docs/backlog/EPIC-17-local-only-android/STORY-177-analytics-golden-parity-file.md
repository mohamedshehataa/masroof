---
id: STORY-177
epic: EPIC-17
title: A golden analytics file, checked against the live server code, pins the expected numbers
phase: 7
status: DONE
risk_tier: high
size: M
modules: [backend]
depends_on: [STORY-176]
reqs: [REQ-AN-143, REQ-AN-144]
implements: ["ADR-0009 D2 parity test 3 (fixtures/analytics/parity-v1.json produced and asserted by a backend integration test)", "PROMPT.md §10 items 1, 2, 4, 5, 6, 7"]
owner_agent: backend-engineer
---

# STORY-177 - A golden analytics file, checked against the live server code, pins the expected numbers

## Goal
Before the server is retired, capture what it computes for a rich, tricky set of expenses in one file,
`fixtures/analytics/parity-v1.json`, and prove the file is right by asserting it against the real `AnalyticsService`
on Testcontainers Postgres. The phone's engine (STORY-178/179) must produce the same numbers from the same file; after
retirement the file is the oracle (ADR-0009 D2). Test-only addition to the frozen backend (ADR-0009 D7).

## Requirements and acceptance criteria
### REQ-AN-143 - The golden file equals what the server computes
**Given** `fixtures/analytics/parity-v1.json` with its seed rows and its expected outputs **When** the backend
integration test (tag `integration`) loads the seed into a Testcontainers Postgres household, sets the month start day,
fixes the clock at each listed `today`, and calls `AnalyticsService` for summary, forecast, by-category, merchants,
heatmap and trend (6 and 12 periods) **Then** every output equals the file field by field (amounts as integer minor
units, basis points as integers, ordering included), for month start days 1 and 25 and at least three `today` values
(first day of a period, a mid-period day, the last day).
Unhappy path: **Given** one expected value in the file is changed by one piaster (the test edits a copy) **When** the
test runs on the copy **Then** it fails naming the output and the field (the comparison is not lenient).
Unhappy path: **Given** the file contains a seed row with a non-integer amount, an unknown enum or a currency that is
not 3 letters **When** loaded **Then** the test fails with a clear message before calling the service.

### REQ-AN-144 - The seed covers every rule that could differ on the phone
**Given** the seed rows of `parity-v1.json` **Then** it contains at least: refunds that net against expenses in the same
category, transfers (never counted), cash withdrawals (counted only with `includeCashWithdrawals`), a USD row
(excluded, not converted), `PENDING_REVIEW` rows (counted), `REJECTED` rows and soft-deleted rows (not counted),
income rows (separate sum), uncategorised rows ("Uncategorised / غير مصنف" bucket), child categories that roll up to
their parent (`Fuel / بنزين` under `Car / السيارة`), merchant names differing only in case and surrounding spaces
(`" Carrefour "`, `carrefour`), a merchant with net zero or negative after refunds (dropped from merchants), and
instants at Cairo midnight on both sides of the April and October DST transitions; and a top-level `_readme` string
in the file states its purpose, version 1 and that it never changes (a change is a new `parity-v2.json`).
Unhappy path: **Given** a coverage check in the same test **When** any category of row listed above is missing from the
seed **Then** the test fails naming the missing case.

## UX states (UI stories only)
N/A (no UI).

## Contract impact
None (no API change; the backend is frozen, test-only addition allowed by ADR-0009 D7).

## Data / migration impact
None (Testcontainers only; no Flyway migration).

## Security / privacy / LLM-safety impact
The seed uses invented merchants and amounts only (no owner data; `scan-secrets` and the anonymize script apply to
`fixtures/`). No production data is read.

## Eval impact
None for the LLM harness. New fixture `fixtures/analytics/parity-v1.json` consumed by the backend integration test and,
in STORY-178/179, the Android unit tests.

## Design notes (architect)
ADR-0009 D2 parity test 3. The Android side reads the file by relative path as `HealthContractTest` reads
`contracts/openapi.yaml`.

## Dependencies
STORY-176 (ordering: the file format is agreed with the Kotlin types; no code dependency)

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: integration test + loader ≈ 220; the JSON fixture is data and excluded)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D2) |
