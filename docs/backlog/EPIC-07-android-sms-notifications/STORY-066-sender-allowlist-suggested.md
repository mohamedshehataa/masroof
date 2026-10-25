---
id: STORY-066
epic: EPIC-07
title: "Sender allowlist with defaults and 'Suggested sources' for unknown money-like senders"
phase: 4
status: IN_PROGRESS
size: M
modules: [android]
depends_on: [STORY-064]
reqs: [REQ-SMS-040, REQ-SMS-041, REQ-SMS-042]
implements: ["PROMPT.md §4.1 (Sender allowlist, Suggested sources)"]
owner_agent: android-engineer
---

# STORY-066 — Sender allowlist with defaults and 'Suggested sources' for unknown money-like senders

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Only financial senders are ever read, and new banks are added with one tap (PROMPT §4.1).

## Requirements and acceptance criteria
### REQ-SMS-040 — Default allowlist of Egyptian banks/wallets/short codes shipped (verify current sender IDs); only allowlisted senders are read/uploaded
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-041 — User adds/removes senders in Settings → Sources
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-042 — Unknown senders with money patterns appear as suggestions for one-tap approval, never auto-ingested
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
None (device-local; optionally synced via `/api/settings` later).

## Data / migration impact
Room `sender_allowlist`.

## Security / privacy / LLM-safety impact
Allowlist-only upload (§12).

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-064

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 4 start |
