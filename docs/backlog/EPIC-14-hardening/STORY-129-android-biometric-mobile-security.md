---
id: STORY-129
epic: EPIC-14
title: "Android biometric app lock and mobile security hardening (MobSF, pinning, network config)"
phase: 9
status: DRAFT
size: M
modules: [android]
depends_on: [STORY-033]
reqs: [REQ-AND-093, REQ-AND-080]
implements: [PROMPT.md §11 Android-specific (Biometric app lock), PROMPT.md S9 (Mobile security)]
owner_agent: android-engineer
---

# STORY-129 — Android biometric app lock and mobile security hardening (MobSF, pinning, network config)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 9. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
The phone that reads bank SMS is locked down (PROMPT §11, S9).

## Requirements and acceptance criteria
### REQ-AND-093 — Optional biometric/device-credential lock on app open and after 5 min background; sensitive screens FLAG_SECURE optional
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-080 — MobSF static scan clean; certificate pinning to our domain with backup pin; allowBackup=false; no exported components without permission
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
lock screen states · RTL · dark

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
MASVS controls; triggers `security-reviewer`.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-033

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
