---
id: STORY-078
epic: EPIC-08
title: "WhatsApp group mode (create group, invite link) and direct 1:1 fallback with daily digest; setup docs"
phase: 5
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-075, STORY-077]
reqs: [REQ-WA-020, REQ-WA-021, REQ-WA-022, REQ-WA-023, REQ-OPS-070]
implements: [PROMPT.md §3.1-3.2, PROMPT.md §15 (SETUP_WHATSAPP.md)]
owner_agent: backend-engineer
---

# STORY-078 — WhatsApp group mode (create group, invite link) and direct 1:1 fallback with daily digest; setup docs

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Both members share one chat with the bot, or fall back to 1:1 chats if the Groups API is limited (PROMPT §3.1-3.2).

## Requirements and acceptance criteria
### REQ-WA-020 — Bot creates the group ('Home Expenses 💸') via the Groups API and generates an invite link exposed in settings
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-021 — Group membership and sender identification per current Meta docs; link status visible via /api/settings
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-022 — WHATSAPP_MODE=group|direct switches to 1:1 chats with the same parser and replies
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-023 — In direct mode a short daily digest is posted to each member
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-070 — docs/SETUP_WHATSAPP.md: Meta app, number registration, webhook config, Groups API, invite link, direct fallback, costs/limits as of build date
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
`Settings.whatsappMode` in v0; add `whatsappGroupInviteUrl`/`linkStatus` (non-breaking).

## Data / migration impact
household columns for group id/link.

## Security / privacy / LLM-safety impact
Human checkpoint: registering the number and first real messages (S16).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-075, STORY-077

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
