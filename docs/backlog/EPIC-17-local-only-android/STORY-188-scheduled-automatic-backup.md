---
id: STORY-188
epic: EPIC-17
title: Back up automatically on a schedule without typing the passphrase again
phase: 9
status: DRAFT
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-184]
reqs: [REQ-OPS-180, REQ-OPS-181]
implements: ["ADR-0009 D5 Destinations (scheduled backup, Keystore-sealed derived key, 'Backup needs attention')"]
owner_agent: android-engineer
---

# STORY-188 - Back up automatically on a schedule without typing the passphrase again

> **DRAFT stub** - first story after the MVP (ADR-0009 Consequences: scheduled backup is the main mitigation for a lost
> phone). REQ ids reserved; full Given/When/Then at /spec.

## Goal
A weekly (or daily) backup is written to the same chosen location without user action (ADR-0009 D5).

## Requirements and acceptance criteria
### REQ-OPS-180 - Periodic backup to a persisted location with a Keystore-sealed derived key
Given scheduled backup is on, when the period elapses, then a WorkManager job writes a new `.masroof` file to the
persisted SAF URI using the cached Argon2id key and salt sealed under a Keystore-wrapped Tink AEAD; the passphrase is
never stored. Validation item: prove a background overwrite through Drive's provider on a real device; if Drive refuses,
fall back to a local folder plus a reminder.

### REQ-OPS-181 - "Backup needs attention" when the job cannot run
Given the Keystore key is lost or the URI permission was revoked, when the job runs, then it stops, notifies "Backup
needs attention" and Settings shows the reason; it never writes an unencrypted or partial file.

## UX states (UI stories only)
Settings switch, frequency, last automatic backup line, attention state; RTL/dark.

## Contract impact
None.

## Data / migration impact
None.

## Security / privacy / LLM-safety impact
Derived-key cache under Keystore (ADR-0009 L3 "The key cache is Keystore-sealed and never exported").

## Eval impact
None.

## Design notes (architect)
ADR-0009 D5 "Destinations".

## Dependencies
STORY-184

## Definition of Ready checklist
- [x] Goal + Part I links
- [ ] Numbered REQs with Given/When/Then incl. unhappy paths (headlines only)
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [ ] Size ≤ 400 changed lines (estimate M; confirm at /spec)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | DRAFT stub - EPIC-17 local-only pivot (ADR-0009 D5) |
