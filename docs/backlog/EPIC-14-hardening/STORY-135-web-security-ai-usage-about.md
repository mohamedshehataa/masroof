---
id: STORY-135
epic: EPIC-14
title: "Web More → Security (sessions, devices), AI usage & cost, About/diagnostics"
phase: 9
status: DESCOPED
size: M
modules: [web]
depends_on: [STORY-031, STORY-050, STORY-120]
reqs: [REQ-UI-070]
implements: ["PROMPT.md §11 Navigation 5 (Security, AI usage & cost, About/diagnostics)"]
owner_agent: web-engineer
---

# STORY-135 — Web More → Security (sessions, devices), AI usage & cost, About/diagnostics

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The remaining More rows exist on the PWA (PROMPT §11).

## Requirements and acceptance criteria
### REQ-UI-070 — Security screen (active sessions/log out everywhere, passkeys when available), AI usage & cost per month, About/diagnostics (version, gitSha, export logs)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
empty · loading · error · offline · RTL · dark

## Contract impact
Uses `/api/settings`, `/auth/logout`, `/health`.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Diagnostics never include raw messages.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-031, STORY-050, STORY-120

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
