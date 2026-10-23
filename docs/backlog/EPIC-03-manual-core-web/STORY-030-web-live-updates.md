---
id: STORY-030
epic: EPIC-03
title: Web live updates from the SSE stream
phase: 2
status: READY
size: S
modules: [web]
depends_on: [STORY-024, STORY-026, STORY-027]
reqs: [REQ-UI-025]
implements: ["PROMPT.md §9 (SSE /api/stream)", "PROMPT.md §13 (cross-device e2e within 5 s)"]
owner_agent: web-engineer
---

# STORY-030 — Web live updates

## Goal
An open PWA session reflects the other member's additions and edits within 5 seconds, without the user doing anything (PROMPT §9, §13).

## Requirements and acceptance criteria
### REQ-UI-025 — Live updates
**Given** a signed-in session **Then** one `EventSource` (or fetch-stream) connection to `/api/stream` is held per tab while the tab is visible, reconnecting with `Last-Event-ID` after a drop; **When** `EXPENSE_CREATED|UPDATED|DELETED` arrives **Then** the matching TanStack Query caches (`expenses` lists, `expense/{id}`, `analytics/summary`) are invalidated or patched so Home and Transactions update without reload, and a non-intrusive toast "Mai added 450 EGP · Groceries" is shown only when the change was made by the other member; **When** `RESYNC` arrives **Then** all queries refetch.
**Given** the Playwright test logs in as Mohamed in one context and Mai in another **When** Mai creates an expense **Then** Mohamed's Home list shows it within 5 s (S13 e2e).
Unhappy path: **Given** the tab becomes hidden **Then** the stream closes after 60 s and reopens on visibility (saves battery on iPhone); **Given** `401` on connect **Then** the token is refreshed once and the connection retried, else the user is logged out as in REQ-UI-011; **Given** 5 reconnect failures in a row **Then** exponential backoff up to 60 s and a subtle "Live updates paused" indicator with manual Retry.

## UX states (UI stories only)
empty: N/A · loading: N/A · error: "Live updates paused" indicator · offline: indicator + resume on reconnect · RTL: toast position mirrored · dark: tokens.

## Contract impact
Uses `GET /api/stream` (v0 + STORY-024 decisions). None new.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Token transport for the stream follows STORY-024's decision; toasts show only amount/category/member.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-024, STORY-026, STORY-027.

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
