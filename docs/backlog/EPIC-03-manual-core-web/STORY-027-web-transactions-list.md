---
id: STORY-027
epic: EPIC-03
title: Web Transactions tab — search, filter chips, day groups, swipe actions and multi-select
phase: 2
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-017, STORY-018, STORY-021, STORY-022]
reqs: [REQ-UI-021, REQ-UI-022]
implements: ["PROMPT.md §11 Navigation 2 (Transactions)", "PROMPT.md §9 (filters)", "PROMPT.md §13 (Playwright: add expense, see it)"]
owner_agent: web-engineer
---

# STORY-027 — Web Transactions tab

## Goal
A member finds any entry quickly, fixes its category with a swipe, and deletes with undo — singly or in bulk (PROMPT §11 Transactions).

## Requirements and acceptance criteria
### REQ-UI-021 — Search, filters, day groups, infinite scroll
**Given** `/transactions` **Then** a search bar (debounced 300 ms → `q`), filter chips for member, category (tree picker), source, status, account and date range (presets: this period, last period, 30 days, custom), each chip showing its active value and an "×" to clear, and "Clear all"; results come from `GET /api/expenses` with the matching parameters, grouped by Cairo calendar day with a sticky header "Wed 22 Oct · 1,450 EGP" (daily total of the loaded rows for that day), loading the next page when the last row is 80% visible (`nextCursor`); each row shows merchant/category, member chip, source icon, amount (red/green only for over-budget/income semantics — plain for normal expenses), and tapping opens `/transactions/{id}` (STORY-029).
**Given** filters are set **Then** they are reflected in the URL query so the view is shareable and survives reload.
Unhappy path: **Given** no results **Then** "Nothing matches these filters" with "Clear all"; **Given** a page request fails mid-scroll **Then** an inline error row with Retry keeps the loaded rows; **Given** a malformed URL filter (`status=FOO`) **Then** it is dropped and a toast says so.

### REQ-UI-022 — Swipe actions and multi-select
**Given** a row on a touch device **When** swiped right (left in RTL — swipe direction follows reading direction) **Then** a "Recategorize" action opens the category picker and `PATCH` updates the row in place; **When** swiped left **Then** "Delete" soft-deletes (`DELETE`) and a 6 s snackbar "Deleted · Undo" restores via `POST .../restore` when tapped; on desktop the same actions are available via a row hover menu and keyboard (`Delete`, `c`).
**Given** long-press (or checkbox mode via the toolbar) **Then** multi-select with a count header and actions Recategorize, Delete (with one undo for the batch) and Merge (disabled with tooltip "Available after Phase 6" until STORY-086 exists — the button must not appear in production builds before then: feature flag `dedup.manualMerge`).
Unhappy path: **Given** the PATCH returns `409` (stale) **Then** the row reloads and a toast "Updated elsewhere — try again"; **Given** undo after the snackbar closed **Then** the entry is reachable in the detail view with `includeDeleted` and a Restore button (STORY-029); **Given** 200 selected rows **Then** bulk operations send requests with a concurrency of 4 and show progress.

## UX states (UI stories only)
empty: filtered vs. truly empty variants · loading: 8 skeleton rows and chip skeletons · error: list-level error with Retry; row-level inline retry · offline: banner; cached first page visible; actions disabled with tooltip · RTL: swipe directions and sticky headers mirrored, Arabic-Indic digits in day totals · dark: tokens; screenshot matrix.

## Contract impact
Uses `GET /api/expenses`, `PATCH`, `DELETE`, `POST .../restore` (v0 + STORY-021/022 additions). None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
None (household data already authorised server-side).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-017, STORY-018, STORY-021, STORY-022.

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
