---
id: STORY-122
epic: EPIC-14
title: "Encrypt sensitive columns (raw message text, OCR, payload) and object storage server-side encryption"
phase: 9
status: DESCOPED
size: M
modules: [backend, infra]
depends_on: [STORY-043, STORY-053]
reqs: [REQ-SEC-042, REQ-SEC-043]
implements: [PROMPT.md §12 (Encrypt sensitive columns at rest)]
owner_agent: backend-engineer
---

# STORY-122 — Encrypt sensitive columns (raw message text, OCR, payload) and object storage server-side encryption

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
A database dump alone does not expose messages (PROMPT §12).

## Requirements and acceptance criteria
### REQ-SEC-042 — raw_message.text/payload, media.ocr_text (and transcripts) encrypted with an app key (AES-GCM or pgcrypto); key rotation documented
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SEC-043 — MinIO/S3 server-side encryption enabled; verified by integration test
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
Expand → migrate (encrypt in place in batches) → contract migrations.

## Security / privacy / LLM-safety impact
Key in .env (`APP_ENCRYPTION_KEY`); rotation runbook.

## Eval impact
None.

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
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 9 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
