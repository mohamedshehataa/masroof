---
id: STORY-046
epic: EPIC-05
title: "SMS template learning: propose a regex after 3+ LLM parses of the same sender format (feature-flagged)"
phase: 3
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-044, STORY-045]
reqs: [REQ-SMS-014, REQ-SMS-015]
implements: [PROMPT.md §4.3 (parsing strategy 3), PROMPT.md S12 (feature flags for template learning)]
owner_agent: ai-parsing-engineer
---

# STORY-046 — SMS template learning: propose a regex after 3+ LLM parses of the same sender format (feature-flagged)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
New SMS formats become free deterministic templates over time, with the admin in the loop (PROMPT §4.3 step 3).

## Requirements and acceptance criteria
### REQ-SMS-014 — After 3 successful LLM parses sharing a sender and skeleton, a PROPOSED template is generated and validated against those messages
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-015 — Admin approves/rejects proposals (PATCH status); flag `parsing.templateLearning` default off in prod
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
Uses `/api/sms-templates` PATCH (v0).

## Data / migration impact
None beyond `sms_template`.

## Security / privacy / LLM-safety impact
Proposed regexes are generated from untrusted text; must be validated (anchored, bounded) before activation.

## Eval impact
Suite `sms`: no regression after a template is activated; calibration metric.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-044, STORY-045

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
