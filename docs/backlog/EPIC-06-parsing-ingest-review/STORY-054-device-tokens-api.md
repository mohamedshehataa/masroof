---
id: STORY-054
epic: EPIC-06
title: Device registration with revocable per-device ingestion tokens
phase: 3
status: DRAFT
size: S
modules: [backend]
depends_on: [STORY-010]
reqs: [REQ-SEC-004, REQ-SEC-009]
implements: [PROMPT.md §9 (per-device bearer tokens; /api/devices), "PROMPT.md §12 (per-device tokens hashed, revocable)", PROMPT.md §8 (device)]
owner_agent: backend-engineer
---

# STORY-054 — Device registration with revocable per-device ingestion tokens

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Each phone and Shortcut has its own revocable credential for ingestion (PROMPT §9, §12).

## Requirements and acceptance criteria
### REQ-SEC-004 — CRUD /api/devices: create returns the token once (hashed in DB), list shows platform/name/last_seen, delete revokes instantly
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SEC-009 — Ingest endpoints authenticate with `Authorization: Bearer <device token>` mapped to member + household; revoked → 401
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/devices*`, `Device`, `DeviceInput`, `DeviceCreated` in v0.

## Data / migration impact
Uses `device` (V2).

## Security / privacy / LLM-safety impact
Auth surface; triggers `security-reviewer`. Token shown once, hashed at rest, last_seen updated.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-010

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (reserved, to be written by /spec)
- [x] UX states (UI stories)
- [x] Contract impact (headline)
- [x] Data / migration impact (headline)
- [x] Security / privacy / LLM-safety impact (headline)
- [x] Eval impact (headline)
- [ ] Size ≤ 400 changed lines (estimate S; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
