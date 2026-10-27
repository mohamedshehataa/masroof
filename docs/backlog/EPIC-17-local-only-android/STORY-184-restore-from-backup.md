---
id: STORY-184
epic: EPIC-17
title: Restore everything from a backup file on a new or wiped phone
phase: 9
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-183]
reqs: [REQ-SEC-068, REQ-OPS-178, REQ-OPS-179]
implements: ["ADR-0009 D5 (restore validation order, staging, atomic swap, marker, re-seal HMAC key, replace-all v1)", "ADR-0009 Threats L3"]
owner_agent: android-engineer
---

# STORY-184 - Restore everything from a backup file on a new or wiped phone

## Goal
On a new phone (or after "Erase all data") the user picks a `.masroof` file, types the passphrase, sees what the backup
holds and its date, and gets back every entry, receipt, budget and setting exactly as they were. A wrong passphrase,
a damaged file or a crafted file never changes anything (ADR-0009 D5 restore validation). Merging restore is out of
scope (v1 replaces everything).

## Requirements and acceptance criteria
### REQ-SEC-068 - Every bad file is refused before anything changes
**Given** Settings -> "Restore from backup" and a picked file **When** it is checked **Then** the checks run in this order
in a staging directory and the first failure stops with no change to Room, receipts, settings or keys:
1. magic `MASROOFB` and known `formatVersion` (a newer version: "Update the app to open this backup"; not a backup:
   "This is not a Masroof backup");
2. KDF parameters within m 19..256 MiB, t 1..10, p 1..4 (otherwise "This backup can't be opened"), checked before any
   key derivation;
3. decryption (wrong passphrase, flipped byte, truncation, reordered segments, edited header: one message "Wrong
   passphrase or damaged file");
4. ZIP entry names on the allowlist (no `..`, no absolute path, no duplicate, nothing outside `manifest.json`, `data/`,
   `keys/`, `receipts/`);
5. total uncompressed size at most 4 GiB and a compression-ratio cap per entry (zip bomb);
6. JSON: integer amounts, ISO-4217 currencies, instants within 2000-01-01..now + 1 day, known enums, every foreign key
   resolvable;
7. every receipt's size and SHA-256 equal the manifest.
Each failure case has a test with a crafted file (KDF m = 4 GiB, t = 1,000; `../../databases/masroof.db` entry; a 10 KB
entry inflating to 5 GiB; amount `12.5`; currency `EGPX`; receipt hash mismatch).
Unhappy path: **Given** the user cancels the picker or the passphrase dialog **Then** nothing is read beyond the header.

### REQ-OPS-178 - The user confirms with the backup's date and counts, then everything is replaced
**Given** a valid file **When** checks 1-7 pass **Then** a confirmation shows "Backup from 27 Oct 2026, 14:05 - 1,240
entries, 37 receipts, 4 budgets. This replaces all data on this phone (N entries)." with Restore and Cancel. **When**
Restore is tapped **Then** one Room transaction replaces every table with the backup's rows, the receipts directory is
swapped by rename, the settings are applied, the local-id HMAC key is re-sealed under this phone's Keystore, and the app
returns to Home showing the restored data.
**Given** export then restore on a wiped install (round-trip test) **Then** every table's rows are identical
(column by column) and every receipt's SHA-256 is identical; an SMS that was recorded before the backup and arrives
again after the restore is not recorded twice (the HMAC key came back).
Unhappy path: **Given** Cancel at the confirmation **Then** the staging directory is deleted and nothing changed.
Unhappy path: **Given** a backup whose data schema version is older than the app's **Then** it is upgraded during
staging (or refused with "This backup is too old" if no upgrade exists), never imported half-mapped.

### REQ-OPS-179 - An interrupted restore finishes or rolls back on the next start
**Given** the process is killed after the Room transaction committed but before the receipts directory swap (test hook)
**When** the app starts **Then** the marker file makes it finish the swap, and the result equals an uninterrupted
restore. **Given** it is killed before the Room transaction committed **Then** the next start removes the staging data
and the phone's previous data is intact.
Unhappy path: **Given** the restore fails while copying receipts into staging (storage full) **Then** "Not enough space
to restore (needs X MB)" is shown and nothing changed.

## UX states (UI stories only)
- Restore flow: pick file -> passphrase -> checking (progress) -> confirmation -> restoring (progress) -> done / error.
- error: the messages above; never a stack trace. offline: works offline (Drive files must be available locally or the
  provider downloads them).
- RTL / dark: strings in `values` + `values-ar`, dates and counts with the digit setting, `FLAG_SECURE` on the
  passphrase screen.

## Contract impact
None.

## Data / migration impact
Replaces all tables in one transaction; no schema change. Uses a marker file and a staging directory in app-private
storage.

## Security / privacy / LLM-safety impact
Trust boundary L3 (backup file -> app): crafted-file defences (KDF bounds before work, zip-slip allowlist, zip bomb caps,
JSON validation, hashes) per ADR-0009 D5; nothing changes until all checks pass. Restoring an old file is a user choice
made with its date shown (rollback threat accepted). security-reviewer sign-off (ADR-0009 L3).

## Eval impact
None.

## Design notes (architect)
ADR-0009 D5 "Restore validation".

## Dependencies
STORY-183

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: validator ≈ 120, swap/marker ≈ 60, UI ≈ 40, tests ≈ 170; crafted fixture files are generated in tests)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D5) |
