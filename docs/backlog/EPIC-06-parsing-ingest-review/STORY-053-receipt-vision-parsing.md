---
id: STORY-053
epic: EPIC-06
title: "Receipt, PDF invoice and InstaPay screenshot parsing with the vision model and total validation"
phase: 3
status: DESCOPED
size: M
modules: [backend, eval]
depends_on: [STORY-045, STORY-047]
reqs: [REQ-RCPT-010, REQ-RCPT-011, REQ-RCPT-012, REQ-RCPT-013, REQ-RCPT-014]
implements: [PROMPT.md §6 step 3 (Receipts), "PROMPT.md §3.4 (receipt photo, PDF invoice, screenshot)", PROMPT.md §8 (media)]
owner_agent: ai-parsing-engineer
---

# STORY-053 — Receipt, PDF invoice and InstaPay screenshot parsing with the vision model and total validation

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
A photo of a faded thermal receipt becomes an entry with line items and a validated total (PROMPT §6 step 3).

## Requirements and acceptance criteria
### REQ-RCPT-010 — Vision extraction of merchant, branch, date, line items, subtotal, VAT 14%, service 12%, tips, discounts, total
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-RCPT-011 — Validation items + tax + service ≈ total (±1 EGP); mismatch lowers confidence and flags
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-RCPT-012 — PDF invoices rendered/extracted and parsed through the same path
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-RCPT-013 — InstaPay/bank-app confirmation screenshots parsed as transfers/payments
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-RCPT-014 — media row with storage key, mime, size, sha256, phash and ocr_text; images stored in MinIO/S3
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`ExpenseItem`, `IngestReceiptForm` in v0.

## Data / migration impact
Uses `media`, `expense_item` (V4).

## Security / privacy / LLM-safety impact
Images are untrusted (instructions inside images → adversarial suite); OCR text encrypted at rest later; only the image is sent to the vision model.

## Eval impact
Suite `receipts` 15 → 60: amount ≥ 92%, line-item F1 ≥ 0.85, total consistency ≥ 95%; receipt p95 < 30 s.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-045, STORY-047

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
