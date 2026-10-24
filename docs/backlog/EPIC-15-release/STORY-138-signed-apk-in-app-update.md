---
id: STORY-138
epic: EPIC-15
title: Signed release APK in CI and in-app update check via /api/app/android/latest
phase: 10
status: DRAFT
size: M
modules: [android, backend, infra]
depends_on: [STORY-137, STORY-129]
reqs: [REQ-AND-001, REQ-AND-110, REQ-OPS-132]
implements: [PROMPT.md S12 (Android app updates), PROMPT.md §11 Android-specific (signed release APK), PROMPT.md §15]
owner_agent: android-engineer
---

# STORY-138 — Signed release APK in CI and in-app update check via /api/app/android/latest

> **DRAFT stub** — to be fully specified by `/spec` at the start of Phase 10. REQ ids below are reserved now so numbering is settled; bodies are headlines only.

## Goal
Sideloaded users still get updates safely (PROMPT S12).

## Requirements and acceptance criteria
### REQ-AND-001 — GET /api/app/android/latest returns versionCode, versionName, url, sha256, changelog, minSupportedVersionCode
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-AND-110 — App checks on launch, prompts to update, downloads and verifies SHA-256 before install; forced update below minSupported
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.

### REQ-OPS-132 — release.yml signs the APK with the keystore from secrets (`TODO(owner)`), uploads to the Release and publishes metadata
(to be specified) — Given/When/Then incl. unhappy paths written by /spec at phase start.


## UX states (UI stories only)
update prompt states · RTL · dark

## Contract impact
`/api/app/android/latest`, `AndroidRelease` in v0.

## Data / migration impact
`android_release` table or static JSON.

## Security / privacy / LLM-safety impact
Hash verification before install; HTTPS only.

## Eval impact
None.

## Design notes (architect)
Filled at stage 3.

## Dependencies
STORY-137, STORY-129

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
| 2026-10-23 | 1 intake | product-analyst | DRAFT stub — REQ ids reserved; full spec at Phase 10 start |
| 2026-10-25 | phase check | product-analyst | stays DRAFT, phase 10: PROMPT §14 item 10 (signed APK with in-app update) is Release, not Phase 1 Foundation |
