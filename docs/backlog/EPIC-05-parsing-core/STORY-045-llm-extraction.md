---
id: STORY-045
epic: EPIC-05
title: "LLM extraction with structured output, schema validation, repair retry, versioned prompts and cost logging"
phase: 3
status: DRAFT
size: M
modules: [backend, eval]
depends_on: [STORY-041, STORY-042, STORY-043]
reqs: [REQ-PARSE-040, REQ-PARSE-041, REQ-PARSE-042, REQ-PARSE-043, REQ-PARSE-044, REQ-PARSE-045]
implements: [PROMPT.md §6 step 3, PROMPT.md S10.4, PROMPT.md §2 (configurable models)]
owner_agent: ai-parsing-engineer
---

# STORY-045 — LLM extraction with structured output, schema validation, repair retry, versioned prompts and cost logging

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Free-text expenses that rules cannot parse (`دفعت ١٥٠ عيش وفول`, `paid 1,450 Carrefour groceries`) are extracted by Claude into a strict schema, with cost and version logged (PROMPT §6 step 3).

## Requirements and acceptance criteria
### REQ-PARSE-040 — Claude tool-use/JSON-schema call returns the §6 schema (type, amount, currency, original_*, merchant_*, category_suggestion, items[], date, time, payment_method, account_last4, paid_by_hint, split_hint, notes, confidence, language, needs_clarification, clarification_question)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-041 — Schema validation; one repair retry with the error; then NEEDS_REVIEW
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-042 — Prompt includes household context (members, accounts, categories, frequent merchants), current Cairo date, few-shot Egyptian examples from the train split
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-043 — ai_call_log row per call: model, prompt version, tokens, cost_micros, latency, raw_message_id
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-044 — Prompts live in versioned files backend/src/main/resources/prompts/<name>.v<N>.md with header; model names in config
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-045 — Prompt caching for static prompt parts; LLM calls mocked in unit tests, real in make eval
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`ParseResult` schema in v0 mirrors the LLM schema; no new paths.

## Data / migration impact
Uses `ai_call_log` (V4).

## Security / privacy / LLM-safety impact
Trust boundary: untrusted text → LLM. Data blocks delimited, no side-effect tools (detailed in STORY-047). Only parsing-relevant fields sent.

## Eval impact
Suites `whatsapp_text`, `sms` (LLM fallback cases): all S10.2 metrics; cost p95 tracked; prompt change requires `/eval`.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-041, STORY-042, STORY-043

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
