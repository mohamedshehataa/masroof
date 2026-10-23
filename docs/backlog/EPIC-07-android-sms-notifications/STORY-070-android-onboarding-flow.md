---
id: STORY-070
epic: EPIC-07
title: "Android onboarding: language, SMS permission explanation, senders, backfill range, notification access, card mapping"
phase: 4
status: DRAFT
size: M
modules: [android]
depends_on: [STORY-033, STORY-040, STORY-066, STORY-068, STORY-069]
reqs: [REQ-AND-030, REQ-AND-031, REQ-AND-032]
implements: [PROMPT.md §11 Onboarding (Android), PROMPT.md §4.1 (explain the permission clearly)]
owner_agent: android-engineer
---

# STORY-070 — Android onboarding: language, SMS permission explanation, senders, backfill range, notification access, card mapping

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
A new install reaches a working SMS pipeline in one guided flow (PROMPT §11). The WhatsApp-link step is STORY-083 (Phase 5) and the budget step STORY-112 (Phase 7).

## Requirements and acceptance criteria
### REQ-AND-030 — Step sequence sign in → language → SMS permission → senders → backfill range → notification access → map cards → done, resumable
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-031 — RECEIVE_SMS/READ_SMS explained before the system dialog; denial state with Settings deep link and 'continue without SMS'
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-032 — Detected card last-4s from the inbox are mapped to payment accounts (create or pick)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline (steps needing network blocked with explanation) · RTL · dark

## Contract impact
Uses `/api/accounts`, `/api/devices`.

## Data / migration impact
DataStore onboarding progress.

## Security / privacy / LLM-safety impact
Permission rationale is a privacy control (§4.1); nothing is read before consent.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-033, STORY-040, STORY-066, STORY-068, STORY-069

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 4 start |
