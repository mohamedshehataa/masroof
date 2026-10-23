---
id: STORY-024
epic: EPIC-02
title: Server-Sent Events stream so open apps update live
phase: 2
status: READY
size: M
modules: [backend]
depends_on: [STORY-020, STORY-022]
reqs: [REQ-UI-001, REQ-UI-006, REQ-UI-007]
implements: ["PROMPT.md §9 (SSE /api/stream)", "PROMPT.md §14 Phase 2 (live updates)", "PROMPT.md §13 (cross-device e2e within 5 s)"]
owner_agent: backend-engineer
---

# STORY-024 — Server-Sent Events stream

## Goal
When one member adds or edits an entry, the other member's open app learns about it within seconds (PROMPT §9, §13).

## Requirements and acceptance criteria
### REQ-UI-001 — Household event stream
**Given** an authenticated member **When** `GET /api/stream` with `Accept: text/event-stream` (token via `Authorization` header, or `?access_token=` for `EventSource` clients — architect decides; both documented) **Then** the response stays open, sends `event: EXPENSE_CREATED|EXPENSE_UPDATED|EXPENSE_DELETED` with `id:` and a `StreamEvent` JSON `data:` (`id, type, at, expenseId, payload{summary}`) for changes in the caller's household only, and a `: heartbeat` comment every 15 s.
Unhappy path: **Given** a change in household H2 **Then** nothing is sent to H1 subscribers (integration test with two households); **Given** an expired token on connect **Then** `401`; **Given** more than 10 concurrent streams per member **Then** `429`.

### REQ-UI-006 — Reconnect with replay
**Given** a client reconnects with `Last-Event-ID: <id>` **When** events were emitted while it was disconnected (≤ 24 h, ≤ 1,000 events buffered per household) **Then** the missed events are replayed in order before live events; **Given** the id is older than the buffer **Then** a single `event: RESYNC` is sent telling the client to refetch.
Unhappy path: **Given** the backend restarts **Then** clients reconnect (standard `retry: 3000`) and receive `RESYNC` (buffer is in-memory in v1; decision recorded, DB-backed buffer if multi-instance later).

### REQ-UI-007 — Delivery latency
**Given** a `POST /api/expenses` commits **When** measured in the integration test **Then** the matching `EXPENSE_CREATED` event reaches an open subscriber within 2 s (p95 over 50 runs), leaving headroom for the 5 s cross-device budget of §13.
Unhappy path: **Given** the transaction rolls back **Then** no event is emitted (publish after commit only).

## UX states (UI stories only)
N/A.

## Contract impact
`GET /api/stream`, `StreamEvent` in v0; the architect decides the `access_token` query parameter addition (non-breaking) and documents the `RESYNC` event type (enum extension — non-breaking for clients that ignore unknown types; note in contract description).

## Data / migration impact
None (in-memory ring buffer).

## Security / privacy / LLM-safety impact
Events carry only ids and short summaries (amount, category, member) — never raw message text; token in query string (if chosen) must be excluded from access logs (Caddy log format) and is short-lived. Triggers `security-reviewer` (new auth surface).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-020, STORY-022.

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
