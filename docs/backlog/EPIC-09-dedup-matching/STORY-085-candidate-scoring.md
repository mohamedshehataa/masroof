---
id: STORY-085
epic: EPIC-09
title: "Dedup candidate window and match scoring (amount, time, merchant similarity, member, last-4, payment method)"
phase: 6
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-049, STORY-051, STORY-052]
reqs: [REQ-DEDUP-020, REQ-DEDUP-021, REQ-DEDUP-022]
implements: ["PROMPT.md §7 (Candidate window, Score)", PROMPT.md S9 (jqwik dedup)]
owner_agent: backend-engineer
---

# STORY-085 — Dedup candidate window and match scoring (amount, time, merchant similarity, member, last-4, payment method)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The system can tell when two arrivals are probably the same purchase (PROMPT §7).

## Requirements and acceptance criteria
### REQ-DEDUP-020 — Candidates: same household, |Δamount| ≤ max(1 EGP, 1%) (or exact when converted), ±48 h
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-DEDUP-021 — Score from amount closeness, time closeness, merchant similarity (normalized + fuzzy + transliteration), same member, same last-4, payment-method compatibility
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-DEDUP-022 — Properties: symmetric score, no self-match, deterministic
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
Index on (household_id, occurred_at, amount_egp_minor).

## Security / privacy / LLM-safety impact
None.

## Eval impact
Suite `dedup`: pairwise precision ≥ 99%, recall ≥ 95%.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-049, STORY-051, STORY-052

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 6 start |
