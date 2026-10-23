---
id: STORY-124
epic: EPIC-14
title: Nightly encrypted Postgres + media backups with weekly automated restore test
phase: 9
status: DRAFT
size: M
modules: [infra]
depends_on: [STORY-003]
reqs: [REQ-OPS-100, REQ-OPS-101]
implements: ["PROMPT.md §12 (Nightly encrypted backups, restore test)", PROMPT.md S13 (Backups)]
owner_agent: devops-engineer
---

# STORY-124 — Nightly encrypted Postgres + media backups with weekly automated restore test

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Data loss is bounded to one day and restores are proven (PROMPT §12).

## Requirements and acceptance criteria
### REQ-OPS-100 — infra/backup.sh: nightly encrypted dump + media sync to configurable target; `make backup`
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-101 — Weekly restore test into a throwaway container counting rows and checking checksums; failure alerts
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Backup encryption key separate from app key.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-003

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 9 start |
