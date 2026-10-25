---
id: STORY-044
epic: EPIC-05
title: "SMS template rules engine: per-sender regex templates with hot reload and direction mapping"
phase: 3
status: BLOCKED
size: M
modules: [backend]
depends_on: [STORY-041, STORY-042, STORY-014]
reqs: [REQ-SMS-002, REQ-SMS-010, REQ-SMS-011, REQ-SMS-012, REQ-SMS-013]
implements: ["PROMPT.md §4.3 (parsing strategy 1, rules)", PROMPT.md §9 (CRUD /api/sms-templates), PROMPT.md §8 (sms_template)]
owner_agent: ai-parsing-engineer
---

# STORY-044 — SMS template rules engine: per-sender regex templates with hot reload and direction mapping

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Bank and wallet SMS are parsed deterministically and for free by regex templates per sender, before any LLM call (PROMPT §4.3).

## Requirements and acceptance criteria
### REQ-SMS-002 — CRUD /api/sms-templates (sender_pattern, regex, field_mapping, status ACTIVE|PROPOSED|DISABLED, hit_count)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-010 — Template extraction of amount, currency, original amount/currency, merchant, last-4, date/time, balance, reference from fixture SMS
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-011 — Templates are hot-reloaded from DB without restart; hit_count increments
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-012 — Direction mapping: debit/credit/refund/reversal/own-transfer/ATM/fee → expense type; balance stored, never an expense
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-013 — Card/account last-4 maps to payment_account and therefore to the owner member
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/sms-templates*`, `SmsTemplate`, `SmsTemplateInput` in v0.

## Data / migration impact
Uses `sms_template` (V4); seed templates for the §4.3 example formats.

## Security / privacy / LLM-safety impact
Regex execution bounded (timeout/length) against ReDoS; SMS bodies never logged.

## Eval impact
Suite `sms` 60 cases at Phase 3: amount ≥ 98%, type ≥ 97%, currency ≥ 99%.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-041, STORY-042, STORY-014

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
