---
id: STORY-042
epic: EPIC-05
title: "Intent classification of incoming text (expense, income, refund, transfer, cash withdrawal, command, OTP, promo, chit-chat)"
phase: 3
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-041]
reqs: [REQ-PARSE-020, REQ-PARSE-021]
implements: [PROMPT.md §6 step 2, PROMPT.md §3.4 (chit-chat ignored), PROMPT.md §4.1 (OTP/promo)]
owner_agent: ai-parsing-engineer
---

# STORY-042 — Intent classification of incoming text (expense, income, refund, transfer, cash withdrawal, command, OTP, promo, chit-chat)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The pipeline decides what a message is before extracting anything, so chit-chat is ignored and OTPs never leave the device-side filter's safety net (PROMPT §6 step 2).

## Requirements and acceptance criteria
### REQ-PARSE-020 — Classify normalized text into EXPENSE|INCOME|REFUND|TRANSFER|CASH_WITHDRAWAL|COMMAND|OTP|PROMO|CHITCHAT|UNKNOWN (rules first, LLM fallback)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-021 — CHITCHAT, OTP and PROMO are never persisted as expenses; OTP text is never forwarded to the LLM
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`ClassifiedType` enum already in v0.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
OTP/promo false-negative rate is a hard 0% gate (S10.2); classification output is bounded to the enum.

## Eval impact
Suites `whatsapp_text`, `sms`, `commands`: type accuracy ≥ 97%; OTP leak hard gate.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-041

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
