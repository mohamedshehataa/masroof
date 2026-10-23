---
id: STORY-077
epic: EPIC-08
title: "WhatsApp text expenses → pipeline → confirmation replies (language-aware, batched, low-confidence question)"
phase: 5
status: DRAFT
size: M
modules: [backend]
depends_on: [STORY-076, STORY-049, STORY-042]
reqs: [REQ-WA-040, REQ-WA-041, REQ-WA-042, REQ-WA-043, REQ-WA-044, REQ-WA-080]
implements: ["PROMPT.md §3.4 (Expense inputs, Bot replies)", PROMPT.md §6 step 7]
owner_agent: backend-engineer
---

# STORY-077 — WhatsApp text expenses → pipeline → confirmation replies (language-aware, batched, low-confidence question)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 5. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
`200 بنزين` in the group becomes an entry and a short confirmation within 10 s (PROMPT §3.4).

## Requirements and acceptance criteria
### REQ-WA-040 — Reply format: '✅ 200 EGP · Fuel ⛽ · Mohamed · today' + month-on-category line + 'Reply to fix: amount / category / person'
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-041 — Reply language follows the incoming message (Arabic/English)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-042 — Low confidence → best guess + one question; entry PENDING_REVIEW
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-043 — Chit-chat is ignored silently
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-044 — Replies rate-limited and batched: a burst of photos gets one combined confirmation
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-WA-080 — Outbound WhatsApp adapter (send text) with retries and 24-hour window/template constraints per current docs
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
Reply linkage stored on raw_message (outbound message id) for corrections.

## Security / privacy / LLM-safety impact
Replies contain only derived data; outbound adapter secrets in .env.

## Eval impact
Suite `whatsapp_text` 60 → 200; text p95 < 10 s.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-076, STORY-049, STORY-042

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
