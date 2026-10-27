---
id: STORY-084
epic: EPIC-09
title: "Exact duplicate detection: same SMS id, WhatsApp id or image hash dropped silently; perceptual hash"
phase: 6
status: DESCOPED
size: S
modules: [backend]
depends_on: [STORY-043, STORY-053]
reqs: [REQ-DEDUP-010, REQ-DEDUP-011]
implements: [PROMPT.md §7 (exact same raw message; image perceptual hash)]
owner_agent: backend-engineer
---

# STORY-084 — Exact duplicate detection: same SMS id, WhatsApp id or image hash dropped silently; perceptual hash

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 6. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Sending the same receipt twice never creates two entries (PROMPT §7).

## Requirements and acceptance criteria
### REQ-DEDUP-010 — Same raw message (sms_id, WhatsApp message id, sha256 of image) → dropped silently with metric
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-DEDUP-011 — Perceptual hash (phash distance ≤ threshold) catches the same receipt sent twice
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
media.phash index.

## Security / privacy / LLM-safety impact
None (reason: hashing only).

## Eval impact
Suite `dedup` exact-duplicate scenarios.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-043, STORY-053

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 6 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
