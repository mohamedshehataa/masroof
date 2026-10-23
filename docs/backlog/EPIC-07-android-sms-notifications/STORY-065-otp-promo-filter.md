---
id: STORY-065
epic: EPIC-07
title: On-device OTP and promotional SMS filtering (OTP never leaves the device)
phase: 4
status: DRAFT
size: S
modules: [android]
depends_on: [STORY-064]
reqs: [REQ-SMS-030, REQ-SMS-031, REQ-SMS-032]
implements: [PROMPT.md §4.1 (Never upload OTPs; ignore promotional SMS), "PROMPT.md §13 (Android: OTP leak = hard failure)"]
owner_agent: android-engineer
---

# STORY-065 — On-device OTP and promotional SMS filtering (OTP never leaves the device)

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 4. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
No one-time password ever reaches the backend (PROMPT §4.1).

## Requirements and acceptance criteria
### REQ-SMS-030 — OTP detection by keywords (OTP, رمز, كود التحقق, verification code, لا تشارك) and patterns; dropped on device
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-031 — OTP leak = hard test failure (0% false negatives on the sms suite negatives)
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-SMS-032 — Promotional SMS classified on device with simple rules and not uploaded; server confirms
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
N/A (no UI).

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Core privacy control; Kover ≥ 80% on filter code.

## Eval impact
Suite `sms` OTP/promo negatives drive the on-device unit tests.

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
- [ ] Size ≤ 400 changed lines (estimate S; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 4 start |
