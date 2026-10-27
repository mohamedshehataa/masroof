---
id: STORY-051
epic: EPIC-06
title: "Merchant normalization, merchant CRUD and learned merchant→category rules"
phase: 3
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-022, STORY-045]
reqs: [REQ-PARSE-003, REQ-PARSE-060, REQ-PARSE-061, REQ-PARSE-062]
implements: ["PROMPT.md §6 step 4 (enrich) and 'Learning from corrections'", "PROMPT.md §9 (merchants, merchant-rules)", PROMPT.md §8]
owner_agent: backend-engineer
---

# STORY-051 — Merchant normalization, merchant CRUD and learned merchant→category rules

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Merchants get clean names and the household's own corrections teach the system before any model is asked (PROMPT §6 step 4).

## Requirements and acceptance criteria
### REQ-PARSE-003 — CRUD /api/merchant-rules (pattern, category, member?, tags, priority, learned_from_correction)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-060 — Merchant normalization: 'CARREFOUR MAADI CITY' → 'Carrefour' via aliases, fuzzy and transliteration
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-061 — Every category/merchant correction writes a merchant_rule that takes priority over the LLM suggestion next time
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-062 — CRUD /api/merchants (normalized name, aliases, default category, logo)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/merchants*`, `/api/merchant-rules*`, `Merchant*`, `MerchantRule*` in v0.

## Data / migration impact
Uses `merchant`, `merchant_rule` (V2).

## Security / privacy / LLM-safety impact
Rules are household data; patterns bounded.

## Eval impact
Category top-1 ≥ 85% → ≥ 92% with learned rules; merchant match ≥ 90%.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-022, STORY-045

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
