---
id: STORY-133
epic: EPIC-14
title: "Error tracking, SLO alerts (WhatsApp + email) and synthetic canary"
phase: 9
status: DRAFT
size: M
modules: [backend, infra]
depends_on: [STORY-003, STORY-077]
reqs: [REQ-OPS-112, REQ-OPS-113, REQ-OPS-114]
implements: [PROMPT.md S13]
owner_agent: sre-observer
---

# STORY-133 — Error tracking, SLO alerts (WhatsApp + email) and synthetic canary

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Operators learn about problems before users do (PROMPT S13).

## Requirements and acceptance criteria
### REQ-OPS-112 — Error tracking (GlitchTip/Sentry) for backend, web, Android with PII scrubbing
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-113 — Alerts: signature failures spike, parse failure > 5%/1h, job lag > 5 min, LLM error > 2%, disk > 80%, backup failed, cert expiry < 14 d — to admin WhatsApp + email
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-114 — Scheduled canary posts a known test message through the pipeline (staging; silent in prod) and verifies the entry
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Alert payloads contain no message bodies.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-003, STORY-077

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
