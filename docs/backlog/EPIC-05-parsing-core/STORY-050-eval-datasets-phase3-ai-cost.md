---
id: STORY-050
epic: EPIC-05
title: Eval datasets at Phase-3 sizes and the monthly AI-cost line in settings
phase: 3
status: DRAFT
size: M
modules: [eval, backend]
depends_on: [STORY-045]
reqs: [REQ-PARSE-095, REQ-PARSE-096]
implements: [PROMPT.md S10.1, PROMPT.md §6 step 3 (monthly AI-cost line), PROMPT.md §14 Phase 3 exit]
owner_agent: ai-eval-engineer
---

# STORY-050 — Eval datasets at Phase-3 sizes and the monthly AI-cost line in settings

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The Phase 3 gate can be measured: datasets are large enough and the household can see what parsing costs (PROMPT S10.1, §6).

## Requirements and acceptance criteria
### REQ-PARSE-095 — Datasets reach S10.1 Phase-3 minimums (sms 60, notifications 20, whatsapp_text 60, commands 30, receipts 15, voice 5, dedup 20, adversarial 20) with train/test splits; real data only via anonymize-fixtures.py
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-096 — Settings.aiCostThisMonthMicros sums ai_call_log for the household period; shown in Settings → AI usage
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`Settings.aiCostThisMonthMicros` in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Datasets never contain raw personal SMS (anonymizer only).

## Eval impact
This story *is* the eval dataset growth; `eval/baseline.json` updated through `/eval`.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-045

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
