---
id: STORY-037
epic: EPIC-04
title: Android Transaction detail — edit any field, split editor, sources, audit history, delete/restore
phase: 2
status: DESCOPED
size: M
modules: [android]
depends_on: [STORY-035, STORY-022, STORY-023]
reqs: [REQ-AND-014]
implements: ["PROMPT.md §11 Key screens (Transaction detail)", "PROMPT.md §9 (PATCH, /split, /restore)"]
owner_agent: android-engineer
---

# STORY-037 — Android Transaction detail

## Goal
Same detail screen as the PWA (STORY-029) natively: inspect and fix every field, split, view sources and history, delete/restore. Receipt-derived sections render when data exists (Phase 3+).

## Requirements and acceptance criteria
### REQ-AND-014 — Transaction detail
**Given** the detail route `expenses/{id}` **Then** the screen shows amount (tap → keypad dialog), merchant with logo/initial avatar, category picker, member chip toggle, account picker, date/time pickers, payment method, tags (chips add/remove), notes, a split editor (NONE/EQUAL/CUSTOM with live sum validation), a Sources timeline (source icon, received time, "View original" expander for text/image via Coil), Audit history from `GET /api/expenses/{id}/audit`, and Delete in the top-bar menu; edits `PATCH` immediately with `If-Match` (optimistic) and show a "Saved" indicator; split saves via `POST .../split`; Delete → confirm → `DELETE` → back with snackbar Undo; a deleted entry shows a banner with Restore.
Unhappy path: **Given** `409` **Then** revert + reload + snackbar; **Given** `404` **Then** "Entry not found" with Back; **Given** CUSTOM shares not summing **Then** Save disabled showing the remainder; **Given** an image fails to load **Then** placeholder with retry; **Given** offline **Then** fields read-only with banner (edit queueing is out of scope for v1 — decision recorded).

## UX states (UI stories only)
empty: N/A · loading: shimmer field list · error: screen-level Retry; field-level revert · offline: read-only + banner · RTL: label/value mirrored, timeline mirrored · dark: theme; TalkBack labels for all controls; Roborazzi matrix.

## Contract impact
Uses `GET/PATCH/DELETE /api/expenses/{id}`, `/split`, `/restore`, `/audit`. None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
"View original" shows raw text/images to household members only; never copied into logs or crash breadcrumbs.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-035, STORY-022, STORY-023.

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
