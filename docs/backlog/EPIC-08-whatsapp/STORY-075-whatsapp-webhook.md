---
id: STORY-075
epic: EPIC-08
title: "WhatsApp Cloud API webhook: verification handshake, signature check, fast ack, idempotent raw persistence"
phase: 5
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-043, STORY-010]
reqs: [REQ-WA-001, REQ-WA-002, REQ-WA-003, REQ-WA-004, REQ-SEC-003]
implements: [PROMPT.md §3.1 (webhook endpoint), PROMPT.md §12, "PROMPT.md §13 (recorded Meta payloads, retries, bad signature)"]
owner_agent: backend-engineer
---

# STORY-075 — WhatsApp Cloud API webhook: verification handshake, signature check, fast ack, idempotent raw persistence

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Meta can deliver group messages to us securely and reliably (PROMPT §3.1). Current Groups API docs are verified at design time; deviations recorded in DECISIONS.md.

## Requirements and acceptance criteria
### REQ-WA-001 — GET /webhooks/whatsapp verification (hub.mode, hub.verify_token, hub.challenge)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-002 — POST /webhooks/whatsapp persists the raw payload and processes asynchronously
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-003 — Idempotent on WhatsApp message id (Meta retries)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-004 — 200 ack in < 1 s at p99
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SEC-003 — X-Hub-Signature-256 verified with the app secret; invalid → 401 and metric
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`GET/POST /webhooks/whatsapp`, `WhatsappWebhookPayload` in v0.

## Data / migration impact
raw_message source WHATSAPP.

## Security / privacy / LLM-safety impact
Webhook trust boundary; triggers `security-reviewer`; secrets `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN` in .env (`TODO(owner)`).

## Eval impact
None (recorded payload tests).

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-043, STORY-010

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 5 start |
