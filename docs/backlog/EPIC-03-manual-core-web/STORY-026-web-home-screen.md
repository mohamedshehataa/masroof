---
id: STORY-026
epic: EPIC-03
title: Web Home tab — period selector, spent-this-month, vs-last-month chip, recent transactions
phase: 2
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-017, STORY-018, STORY-021, STORY-025]
reqs: [REQ-UI-020]
implements: ["PROMPT.md §11 Navigation 1 (Home)", "PROMPT.md §10 item 1"]
owner_agent: web-engineer
---

# STORY-026 — Web Home tab

## Goal
On opening the PWA a member sees how much the household spent this period, how that compares with last period, and the 10 most recent entries (PROMPT §11 Home). Budget ring/bars, needs-review banner, upcoming bills and the AI insight card are added by STORY-108 in Phase 7 and render only when their data exists.

## Requirements and acceptance criteria
### REQ-UI-020 — Home tab v1
**Given** a signed-in member on `/` **Then** the page shows: a period selector (current period label from `AnalyticsSummary.period.label`, e.g. "25 Sep – 24 Oct", with previous/next arrows and "This month" reset), the big `totalSpend` formatted per REQ-UI-005 with tabular numerals, a chip "vs last month ▲ 12% (+1,250 EGP)" coloured semantic red when up / green when down / neutral when `deltaPct` is null ("No data last month"), a "By member" row with member colour chips and amounts, and a "Recent" list of the latest 10 expenses (`GET /api/expenses?limit=10`) each showing merchant/category name, member chip, amount, relative time ("2 h ago") and a source icon (WhatsApp, SMS, receipt, manual, push) with a "See all" link to `/transactions`.
**When** another member creates an expense **Then** the list and totals update (via STORY-030) without a reload.
**Given** pull-to-refresh on mobile or the refresh button on desktop **Then** both queries refetch and the skeleton is not shown again (stale-while-revalidate).
Unhappy path: **Given** `GET /api/analytics/summary` fails **Then** the number area shows an error card with Retry while the recent list still renders (independent queries); **Given** no expenses yet **Then** an empty state "No expenses yet — add your first one" with a button to `/add`; **Given** the previous period arrow goes beyond 24 months back **Then** the arrow is disabled.

## UX states (UI stories only)
empty: illustration + CTA to Add · loading: skeleton for number, chip, 10 list rows · error: per-section error cards with Retry · offline: banner; last cached data shown (TanStack persisted cache) with "Updated 5 min ago" · RTL: period arrows mirrored, amounts `١٬٢٥٠ ج.م`, member chips right-aligned · dark: tokens; Roborazzi-equivalent Playwright screenshots LTR/RTL × light/dark × mobile/desktop.

## Contract impact
Uses `GET /api/analytics/summary`, `GET /api/expenses` (v0). None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (reads household aggregates; no raw message text shown).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-017, STORY-018, STORY-021, STORY-025.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1-2 spec | product-analyst | READY |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
