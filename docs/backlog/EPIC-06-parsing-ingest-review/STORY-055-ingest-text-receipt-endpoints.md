---
id: STORY-055
epic: EPIC-06
title: "Ingest endpoints for free text and receipt uploads, parse dry-run, rate limiting"
phase: 3
status: DESCOPED
size: M
modules: [backend]
depends_on: [STORY-043, STORY-045, STORY-053, STORY-054]
reqs: [REQ-PARSE-001, REQ-PARSE-002, REQ-PARSE-080, REQ-RCPT-001, REQ-SEC-030]
implements: ["PROMPT.md §9 (/api/ingest/*)", PROMPT.md §1 items 5-6, PROMPT.md §12 (rate limiting on ingest)]
owner_agent: backend-engineer
---

# STORY-055 — Ingest endpoints for free text and receipt uploads, parse dry-run, rate limiting

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 3. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Apps and Shortcuts can push any text or receipt to the backend and get an entry out of the pipeline (PROMPT §9).

## Requirements and acceptance criteria
### REQ-PARSE-001 — POST /api/ingest/text → 202 IngestAccepted {rawMessageId}; processed asynchronously
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-002 — POST /api/parse/dry-run returns ParseResult without side effects (used by eval and preview UI)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PARSE-080 — Idempotency on (deviceId, clientMessageId) for text and sha256 for receipts; duplicates → 202 with the same id
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-RCPT-001 — POST /api/ingest/receipt multipart (image/PDF ≤ 10 MB) → 202; stored in object storage
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SEC-030 — Rate limiting on all /api/ingest/* per device (e.g. 60/min) → 429 with Retry-After
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`/api/ingest/text`, `/api/ingest/receipt`, `/api/parse/dry-run`, `IngestTextRequest`, `IngestReceiptForm`, `IngestAccepted`, `ParseRequest/Result` in v0.

## Data / migration impact
None beyond V4.

## Security / privacy / LLM-safety impact
Ingest trust boundary; triggers `security-reviewer`; size limits, content-type sniffing, rate limits.

## Eval impact
Dry-run is the eval adapter's entry point.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-043, STORY-045, STORY-053, STORY-054

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
