---
id: STORY-039
epic: EPIC-04
title: Android live updates from the SSE stream
phase: 2
status: READY
size: S
modules: [android]
depends_on: [STORY-024, STORY-038]
reqs: [REQ-AND-016]
implements: ["PROMPT.md §9 (SSE /api/stream)", "PROMPT.md §14 Phase 2 (live updates)"]
owner_agent: android-engineer
---

# STORY-039 — Android live updates

## Goal
While the Android app is in the foreground it reflects the other member's changes within 5 seconds (PROMPT §9, §13).

## Requirements and acceptance criteria
### REQ-AND-016 — Foreground live updates
**Given** the app is in the foreground with a session **Then** one OkHttp SSE connection to `/api/stream` is held (lifecycle-aware: started in `onStart`, closed 60 s after `onStop`), reconnecting with `Last-Event-ID`; **When** `EXPENSE_CREATED|UPDATED|DELETED` arrives **Then** the Room cache is upserted/deleted from the event payload (or the entry refetched when the payload is a summary), so every observing screen updates, and a snackbar "Mai added 450 EGP · Groceries" shows only for the other member's changes; `RESYNC` triggers a full refresh of the current period.
Unhappy path: **Given** `401` on connect **Then** one refresh then retry, else logout per REQ-AND-007; **Given** repeated failures **Then** backoff to 60 s with a subtle "Live updates paused" indicator; **Given** Doze/background **Then** no connection is kept (battery rule of §11 — background freshness comes from push in Phase 4).

## UX states (UI stories only)
empty: N/A · loading: N/A · error: paused indicator · offline: resumes on connectivity · RTL: snackbar text direction · dark: theme.

## Contract impact
Uses `GET /api/stream` (STORY-024 decisions). None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Follows STORY-024's token transport decision; no event payload persisted beyond cache fields.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-024, STORY-038.

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
