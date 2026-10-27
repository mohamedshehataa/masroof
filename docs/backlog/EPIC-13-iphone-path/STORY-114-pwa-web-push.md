---
id: STORY-114
epic: EPIC-13
title: Web push (iOS 16.4+) for budget alerts and review requests; backend web-push sender
phase: 8
status: DESCOPED
size: M
modules: [web, backend]
depends_on: [STORY-113, STORY-071]
reqs: [REQ-PWA-020, REQ-PWA-021]
implements: [PROMPT.md §11 PWA-specific (Web push), PROMPT.md §6 step 7]
owner_agent: web-engineer
---

# STORY-114 — Web push (iOS 16.4+) for budget alerts and review requests; backend web-push sender

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 8. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2 is notified on the iPhone without a native app (PROMPT §11).

## Requirements and acceptance criteria
### REQ-PWA-020 — Push subscription flow with permission rationale; subscription registered via /api/devices (platform WEB)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-PWA-021 — Backend sends VAPID web push for BUDGET_ALERT and review requests; click opens the right route
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
permission denied state · offline · RTL · dark

## Contract impact
`DeviceInput.pushSubscription` addition (non-breaking).

## Data / migration impact
`device.push_subscription jsonb`.

## Security / privacy / LLM-safety impact
VAPID keys in .env; payload ids and short summary only.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-113, STORY-071

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 8 start |
| 2026-10-27 | descope | product-analyst | superseded by ADR-0009 local-only Android (EPIC-17) |
