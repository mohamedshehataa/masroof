---
id: STORY-191
epic: EPIC-17
title: Lock the app with fingerprint, face or the phone PIN
phase: 9
status: DRAFT
risk_tier: low
size: S
modules: [android]
depends_on: [STORY-169]
reqs: [REQ-SEC-070]
implements: ["PROMPT.md §11 Android-specific (biometric lock optional)", "ADR-0009 Threats L5 (accepted risk until a lock exists)"]
owner_agent: android-engineer
---

# STORY-191 - Lock the app with fingerprint, face or the phone PIN

> **DRAFT stub** - reuses the lock part of STORY-129 (descoped; pinning/network parts are obsolete). REQ id reserved.

## Goal
Someone holding the unlocked phone cannot open the household's spending without the owner's biometric or PIN.

## Requirements and acceptance criteria
### REQ-SEC-070 - Optional lock with BiometricPrompt (strong biometric or device credential)
Given the lock is on, when the app is opened or returns after more than N minutes in the background, then content is
hidden until BiometricPrompt succeeds; recents thumbnails are hidden; capture keeps working while locked; turning the
lock off needs a successful prompt. SQLCipher encryption of Room stays out of scope (REQ-SEC-007 follow-up).

## UX states (UI stories only)
Lock screen, fallback to device credential, error/lockout; RTL/dark.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Reduces ADR-0009 L5 exposure on an unlocked phone; not a defence against root.

## Eval impact
None.

## Design notes (architect)
To be filled.

## Dependencies
STORY-169

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate S; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot, reuses STORY-129 lock |
