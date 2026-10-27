---
id: STORY-057
epic: EPIC-06
title: "Review inbox API: list pending/low-confidence items and resolve them"
phase: 3
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-049, STORY-022]
reqs: [REQ-EXP-006, REQ-EXP-030]
implements: [PROMPT.md §9 (/api/review), PROMPT.md §11 Key screens (Review inbox actions)]
owner_agent: backend-engineer
---

# STORY-057 — Review inbox API: list pending/low-confidence items and resolve them

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Everything the pipeline was unsure about can be confirmed or fixed in one place (PROMPT §9, §11).

## Requirements and acceptance criteria
### REQ-EXP-006 — GET /api/review lists PENDING_REVIEW and POSSIBLE_DUPLICATE entries with reason, confidence and source summary
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-EXP-030 — POST /api/review/{id}/resolve actions CONFIRM | EDIT (patch) | NOT_AN_EXPENSE (IGNORED) | IGNORE_SENDER; audit + merchant-rule learning
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/review`, `/api/review/{expenseId}/resolve`, `ReviewItem`, `ReviewPage`, `ResolveRequest`, `ReviewReason` in v0 (MERGE action added by Phase 6).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
IGNORE_SENDER writes to the device allowlist (Phase 4) — admin only.

## Eval impact
Resolutions feed the production feedback loop (S13).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-049, STORY-022

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 3 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
