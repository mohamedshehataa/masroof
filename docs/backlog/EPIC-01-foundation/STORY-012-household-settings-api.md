---
id: STORY-012
epic: EPIC-01
title: Household settings read and update (month start day, currency, WhatsApp mode, ATM rule)
phase: 1
status: DESCOPED
size: S
modules: [backend]
depends_on: [STORY-010]
reqs: [REQ-EXP-010, REQ-EXP-011]
implements: ["PROMPT.md §9 (GET/PATCH /api/settings)", "PROMPT.md §8 (household)", "PROMPT.md §10 (month start day)", "PROMPT.md §4.3 (count ATM withdrawals setting)"]
owner_agent: backend-engineer
---

# STORY-012 — Household settings read and update

## Goal
Both apps read and the admin edits the household settings that drive analytics periods and ingestion behaviour (PROMPT §9, §10).

## Requirements and acceptance criteria
### REQ-EXP-010 — Read settings
**Given** an authenticated member **When** `GET /api/settings` **Then** `200 Settings` with `householdId, householdName, baseCurrency: "EGP", timezone: "Africa/Cairo", monthStartDay (default 1), whatsappMode (default GROUP), countAtmWithdrawalsAsSpending (default false), autoConfirmThreshold (default 0.85), weeklySummaryDay (default FRI), aiModels {fast, vision} from config, aiCostThisMonthMicros (0 until Phase 3), members[]` (active members with `id, displayName, role, color, phoneE164` masked to last 4 digits for non-admins).
Unhappy path: **Given** no token **Then** `401`.

### REQ-EXP-011 — Admin updates settings
**Given** an ADMIN **When** `PATCH /api/settings` with `{monthStartDay: 25}` **Then** `200` with the updated `Settings`, an `audit_log` row `SETTINGS_CHANGED` with before/after values, and subsequent period computations use day 25 (asserted by STORY-025's tests).
Unhappy path: **Given** `monthStartDay: 31` or `0` **Then** `422` (`1..28` so every month has the day); **Given** `autoConfirmThreshold: 0.3` **Then** `422` (`0.5..1`); **Given** a MEMBER calls PATCH **Then** `403`; **Given** an unknown property `timezone` in the patch **Then** `422` (`timezone` and `baseCurrency` are read-only in v1 — recorded as a decision; changing them would re-base history).

## UX states (UI stories only)
N/A.

## Contract impact
`GET/PATCH /api/settings`, schemas `Settings`, `SettingsPatch` in v0 (unchanged).

## Data / migration impact
Columns on `household` (V1/V2): add `whatsapp_mode`, `count_atm_as_spending`, `auto_confirm_threshold`, `weekly_summary_day` if absent → migration `V6__household_settings.sql` (expand, with defaults).

## Security / privacy / LLM-safety impact
Admin-only write; phone numbers masked for non-admins. No LLM involvement.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-010.

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories) — N/A
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
