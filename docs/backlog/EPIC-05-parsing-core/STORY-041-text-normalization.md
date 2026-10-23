---
id: STORY-041
epic: EPIC-05
title: "Text normalization: Unicode, Arabic-Indic digits, separators, multipliers, currency words"
phase: 3
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-005]
reqs: [REQ-PARSE-010, REQ-PARSE-011, REQ-PARSE-012, REQ-PARSE-013]
implements: [PROMPT.md §6 step 1, PROMPT.md §3.4 (examples), "PROMPT.md S9 (jqwik: normalization idempotent, Arabic-Indic ≡ Western)"]
owner_agent: ai-parsing-engineer
---

# STORY-041 — Text normalization: Unicode, Arabic-Indic digits, separators, multipliers, currency words

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Every incoming text is normalized deterministically before classification so `بنزين ٢٠٠` and `200 بنزين` yield the same amount (PROMPT §6 step 1).

## Requirements and acceptance criteria
### REQ-PARSE-010 — Unicode normalization and Arabic-Indic digits to Western (original kept)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-011 — Thousands/decimal separators unified ('1,250' / '1.250,00' / '١٬٢٥٠')
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-012 — Multipliers expanded (k, الف, ألف, مية; '5k rent', '2.5 الف موبيليا')
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-013 — Currency symbols and words mapped (ج.م, جنيه, EGP, LE, $, USD, دولار)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None (internal domain service; exposed later via `POST /api/parse/dry-run`).

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Input is untrusted text; normalization must be bounded (max 4,000 chars) and never throw on malformed Unicode.

## Eval impact
Suites `whatsapp_text`, `sms`: amount exact match ≥ 96%/98%; property tests idempotency.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-005

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
