---
id: STORY-158
epic: EPIC-16
title: "Owner-set inflation figure for category creep"
phase: 7
status: DESCOPED
size: S
modules: [backend, contracts]
depends_on: [STORY-157, STORY-012]
reqs: [REQ-AN-120, REQ-AN-121]
implements: ["Owner request 2026-10-25 (category creep; inflation context for Egypt, owner-set CPI, never fetched by an LLM)", "PROMPT.md §9 (GET/PATCH /api/settings)"]
risk_tier: high
owner_agent: backend-engineer
---

# STORY-158 — Owner-set inflation figure for category creep

## Goal
The admin can enter a yearly inflation figure (for example Egypt's latest headline CPI rate), and the creep card says
whether a rising category is rising faster than that figure. The figure is typed in by the owner; Masroof never
fetches it and no LLM produces it (owner request 2026-10-25).

## Requirements and acceptance criteria
### REQ-AN-120 — Inflation reference setting
`Settings.inflationReference` = `{annualRateBps: 0..10000, asOf: date, label: string ≤ 60 chars} | null` (default
`null`), readable by every member, set or cleared (`null`) by the admin with `PATCH /api/settings`, audit-logged as
`SETTINGS_CHANGED`.
**Given** an ADMIN **When** `PATCH {"inflationReference": {"annualRateBps": 2640, "asOf": "2026-09-10", "label": "CAPMAS urban CPI, Aug 2026"}}`
**Then** `200` with the value stored and an `audit_log` row.
**Given** `PATCH {"inflationReference": null}` **Then** `200` and the comparison disappears from the next report.
Unhappy paths:
- **Given** `annualRateBps` of `10001`, `-1` or `26.4` (non-integer) **Then** `422` naming the field.
- **Given** `asOf` later than today in Cairo, or a `label` of 61 characters **Then** `422`.
- **Given** a MEMBER sends the PATCH **Then** `403`.
- **Given** any request path **Then** the backend makes no outbound network call and no LLM call to obtain or check
  the figure (asserted by a test with outbound HTTP disabled).

### REQ-AN-121 — Creep compared with inflation
When `inflationReference` is set, each creeping category (STORY-157 REQ-AN-118) gets
`inflationOverSpanBps = ⌊annualRateBps × 3 / 12⌋` (3 monthly steps, simple, not compounded),
`realGrowthBps = growthBps − inflationOverSpanBps` and `aboveInflation = realGrowthBps > 0`. Detection itself is
unchanged. The card's first sentence becomes `habits.creep.foundReal` when the first category is above inflation, or
`habits.creep.withInflation` when it is not; `highlight` is true only when at least one creeping category is above
inflation. `detail` gains `inflation {annualRateBps, asOf, label, inflationOverSpanBps, stale}` and per category
`realGrowthBps`, `aboveInflation`. `params` gain `inflationRateBps` (the `{inflationRate}` placeholder; clients show it with one decimal, floored:
2640 → 26.4),
`realGrowthPct` (⌊realGrowthBps / 100⌋), and `asOf` when stale.

| key | English | Arabic |
|---|---|---|
| `habits.creep.foundReal` | {category} has gone up {steps} months in a row: from {first} to {last} (+{growthPct}%), about {realGrowthPct}% more than the {inflationRate}% yearly inflation figure you set. | {category} بيزيد {steps} شهور ورا بعض: من {first} لـ {last} (+{growthPct}٪)، يعني حوالي {realGrowthPct}٪ أكتر من التضخم اللي حطيته ({inflationRate}٪ في السنة). |
| `habits.creep.withInflation` | {category} went from {first} to {last} (+{growthPct}%), close to the {inflationRate}% yearly inflation figure you set. | {category} زاد من {first} لـ {last} (+{growthPct}٪)، قريب من التضخم اللي حطيته ({inflationRate}٪ في السنة). |
| `habits.creep.inflationStale` (appended when stale) | Your inflation figure is from {asOf}; you can update it in Settings. | رقم التضخم عندك من {asOf}، ممكن تحدّثه من الإعدادات. |

**Given** `annualRateBps = 2640` and the Dining out creep from STORY-157 (`growthBps = 3,333`) **Then**
`inflationOverSpanBps = 660`, `realGrowthBps = 2,673`, `aboveInflation = true`, `messageKey = habits.creep.foundReal`,
`highlight = true`, and English renders "... (+33%), about 26% more than the 26.4% yearly inflation figure you set."
**Given** `annualRateBps = 9000` and a creeping category with `growthBps = 2,100` **Then** `inflationOverSpanBps = 2,250`,
`aboveInflation = false`, `messageKey = habits.creep.withInflation`, `highlight = false` (if no other category is above).
Unhappy paths:
- **Given** `asOf` more than 365 days before today **Then** `inflation.stale = true` and `habits.creep.inflationStale`
  is appended; the comparison is still shown.
- **Given** `inflationReference = null` **Then** the card is exactly STORY-157's (no inflation fields, no wording change).
- **Given** no creeping category **Then** the inflation figure changes nothing (`habits.creep.none`).
- **Given** nudges (STORY-159) **Then** they never use the inflation figure.

## UX states (UI stories only)
N/A (the admin input lives in STORY-162/164 settings screens).

## Contract impact
`Settings.inflationReference`, `SettingsPatch.inflationReference` (nullable object), `InflationReference` schema;
`CategoryCreepDetail` gains optional `inflation`, `realGrowthBps`, `aboveInflation`. Additive.

## Data / migration impact
New migration (expand): nullable `household` columns for rate (integer bps), as-of date and label.

## Security / privacy / LLM-safety impact
Owner-entered number only; no network fetch, no LLM (explicit owner constraint, test-enforced). The label is
user text: length-limited, never logged, rendered as text (no HTML).

## Eval impact
None (no AI).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-157 (creep detection), STORY-012 (settings API).

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (≈ 45 production + ≈ 95 tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-26 | 1 spec | product-analyst | DRAFT — fully specified (EPIC-16, owner request) |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
