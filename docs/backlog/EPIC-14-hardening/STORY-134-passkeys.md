---
id: STORY-134
epic: EPIC-14
title: Passkey (WebAuthn) login as an alternative to passwords — feasibility and implementation
phase: 9
status: DRAFT
size: M
modules: [backend, web]
depends_on: [STORY-009, STORY-018]
reqs: [REQ-SEC-050]
implements: [PROMPT.md §9 (passkeys if feasible)]
owner_agent: backend-engineer
---

# STORY-134 — Passkey (WebAuthn) login as an alternative to passwords — feasibility and implementation

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Member 2 signs in with Face ID (PROMPT §9).

## Requirements and acceptance criteria
### REQ-SEC-050 — Register and authenticate with passkeys on the PWA (platform authenticator), fallback to password; may be DESCOPED with an ADR if infeasible
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
registration/login states · RTL · dark

## Contract impact
New `/auth/passkey/*` paths (non-breaking).

## Data / migration impact
`passkey_credential` table.

## Security / privacy / LLM-safety impact
Auth surface; `security-reviewer`.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-009, STORY-018

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
