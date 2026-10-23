---
id: STORY-115
epic: EPIC-13
title: "PWA offline: cached app shell and last-viewed data; queued manual entries sync on reconnect"
phase: 8
status: DRAFT
size: M
modules: [web]
depends_on: [STORY-113, STORY-028]
reqs: [REQ-PWA-030, REQ-PWA-031]
implements: [PROMPT.md §11 PWA-specific (Offline), "PROMPT.md §13 (Web: offline queue)"]
owner_agent: web-engineer
---

# STORY-115 — PWA offline: cached app shell and last-viewed data; queued manual entries sync on reconnect

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 8. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The PWA works in the metro (PROMPT §11).

## Requirements and acceptance criteria
### REQ-PWA-030 — Service worker caches the app shell and last-viewed API responses (stale-while-revalidate)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PWA-031 — Quick add while offline queues in IndexedDB with Idempotency-Key and syncs on reconnect (Background Sync where available); pending indicator
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
offline banner · pending rows · sync error state · RTL · dark

## Contract impact
None.

## Data / migration impact
IndexedDB schema.

## Security / privacy / LLM-safety impact
Cached data is per-browser profile; cleared on logout.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-113, STORY-028

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 8 start |
