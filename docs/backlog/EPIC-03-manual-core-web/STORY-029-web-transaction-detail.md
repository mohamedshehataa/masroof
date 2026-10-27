---
id: STORY-029
epic: EPIC-03
title: Web Transaction detail — edit any field, split editor, sources, audit history, delete/restore
phase: 2
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-027, STORY-022, STORY-023]
reqs: [REQ-UI-024]
implements: ["PROMPT.md §11 Key screens (Transaction detail)", "PROMPT.md §9 (PATCH, /split, /restore)"]
owner_agent: web-engineer
---

# STORY-029 — Web Transaction detail

## Goal
A member opens any entry, sees everything known about it and fixes anything in place (PROMPT §11 Transaction detail). Line items, receipt image zoom and "Always categorize this merchant as…" render once Phase 3 data exists (fields are already in the contract; the components show only when present).

## Requirements and acceptance criteria
### REQ-UI-024 — Transaction detail
**Given** `/transactions/{id}` **Then** the screen shows: amount (tap to edit inline with the keypad), merchant with logo or initial avatar, category (picker), member (chip toggle), account (picker), date/time (picker, Cairo), payment method, tags (chips, add/remove), notes (textarea), a split editor (NONE / EQUAL / CUSTOM with two share fields that must sum to the total — live validation), a "Sources" timeline listing each `sources[]` entry with source icon, received time and a "View original" expander (text or image), an "Audit history" section from `GET /api/expenses/{id}/audit` ("Mai changed category Groceries → Dining · 2 h ago"), and a Delete button.
**When** a field is changed **Then** `PATCH` is sent immediately (optimistic update, `If-Match`) and a subtle "Saved" indicator appears; the split editor saves via `POST .../split`.
**When** Delete is tapped **Then** confirm dialog → `DELETE` → navigate back with "Deleted · Undo" snackbar; **Given** the entry is deleted (`?includeDeleted=true`) **Then** a banner "This entry was deleted" with Restore.
Unhappy path: **Given** `409` on PATCH **Then** the optimistic value reverts, data reloads and a toast explains; **Given** `404` **Then** "Entry not found" with a link to Transactions; **Given** CUSTOM shares not summing **Then** Save disabled with the difference shown ("5 EGP left"); **Given** the original source is an image that fails to load **Then** a placeholder with a retry link.

## UX states (UI stories only)
empty: N/A · loading: skeleton of the field list · error: page-level error with Retry; field-level revert toasts · offline: fields read-only with banner · RTL: label/value order mirrored, timeline on the right, Arabic relative times · dark: tokens; all controls labelled; axe clean.

## Contract impact
Uses `GET/PATCH/DELETE /api/expenses/{id}`, `/split`, `/restore`, `/audit` (STORY-022 addition), `RawMessageSummary`. None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
"View original" displays raw message text/images to household members only (already authorised); the UI must not copy raw text into analytics/telemetry events.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-027, STORY-022, STORY-023.

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
