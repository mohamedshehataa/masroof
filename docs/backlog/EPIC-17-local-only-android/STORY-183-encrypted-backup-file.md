---
id: STORY-183
epic: EPIC-17
title: Save an encrypted backup of everything to a file the user chooses
phase: 9
status: READY
risk_tier: high
size: M
modules: [android]
depends_on: [STORY-174, STORY-181]
reqs: [REQ-SEC-066, REQ-SEC-067, REQ-OPS-176, REQ-OPS-177]
implements: ["ADR-0009 D5 (KDF, cipher, file layout, plaintext ZIP, manual SAF destination, threat model)", "PROMPT.md §12 (backups + export), superseded server parts per ADR-0009 D1"]
owner_agent: android-engineer
---

# STORY-183 - Save an encrypted backup of everything to a file the user chooses

## Goal
Room is now the only copy, so a lost phone must not mean lost history. Settings -> "Back up now" writes one
`.masroof` file (Argon2id + Tink streaming AEAD, ADR-0009 D5) through the system file picker, e.g. to Google Drive,
holding every entry, category, account, member, budget, source link, learned template, setting, the local-id key and
every receipt; never the review text. Restore is STORY-184; scheduled backup is STORY-188.

## Requirements and acceptance criteria
### REQ-SEC-066 - The file is encrypted and tamper-evident
**Given** a backup written with passphrase `correct horse battery` **Then** the file starts with the 8 bytes `MASROOFB`,
`formatVersion` 1, `kdfId` 1 (Argon2id v1.3, m = 65,536 KiB, t = 3, p = 1), a 16-byte random salt, `aeadId` 1 and reserved
0, big-endian as in ADR-0009 D5, followed by a Tink `AES256_GCM_HKDF_1MB` stream whose associated data is that header;
the key is Argon2id over the NFC-normalised UTF-8 passphrase (32 bytes). The Argon2id implementation passes the RFC 9106
Argon2id test vector in a JVM unit test.
**Given** two backups of the same data with the same passphrase **Then** their salts and ciphertexts differ.
Unhappy path: **Given** the file with any single byte of the header or body flipped, the last 100 bytes cut off, or two
1 MiB segments swapped **When** decrypted with the right passphrase **Then** decryption fails (verified here by a test
decryptor; user-facing restore handling is STORY-184).
Unhappy path: **Given** the plaintext ZIP **Then** no SMS/notification text from `review_item`, no OTP fixture string, no
full account number and no session token appears in it (test writes known strings into those tables and greps the
decrypted bytes).

### REQ-SEC-067 - The passphrase is strong enough and its loss is explained
**Given** the first backup **When** the user opens "Back up now" **Then** a screen explains "If you forget this
passphrase, nobody can open this backup - not even us" and requires a checkbox before continuing; the passphrase must be
at least 12 code points after NFC normalisation and is typed twice.
Unhappy path: **Given** 11 code points, two different entries, or an empty field **Then** Continue stays disabled with the
reason shown.
Unhappy path: **Given** an Arabic passphrase `كلمة سر طويلة جدا` **Then** it is accepted and the same text typed with a
different Unicode composition form opens the file (NFC).
The passphrase is never stored, logged or kept in memory after the write ends (the field is cleared).

### REQ-OPS-176 - The backup contains everything needed to rebuild the phone
**Given** a phone with entries (incl. soft-deleted ones within 30 days), sources, receipts (attached and unattached),
categories, accounts, members, budgets, learned templates and settings (month start day, ATM rule, sender allowlist,
capture switches, digit and language settings) **When** a backup is written **Then** the decrypted ZIP has
`manifest.json` first (format version 1, data schema version, app version, `createdAtUtc`, row count per table, size
and SHA-256 per receipt), then `data/*.jsonl` for expenses, categories, accounts, members, budgets, expense_sources,
receipts, learned_templates, `data/settings.json`, `keys/local-id.key` and `receipts/<uuid>.<ext>`; amounts are JSON
integers (minor units) with an ISO-4217 code, instants are UTC epoch milliseconds; `review_item`, `captured_tx`,
`outbox`, `parsed_outbox`, session and server data are absent.
Unhappy path: **Given** a receipt file that is missing on disk **Then** the backup still completes, the manifest lists
it as missing and the result message says "1 receipt file was missing and is not in the backup".
Unhappy path: **Given** 500 MB of receipts **Then** the backup completes with memory use bounded by the 1 MiB segment
(streams; nothing is loaded whole) - checked with a large-file unit test under a small heap.

### REQ-OPS-177 - The file is written where the user chooses, completely or not at all
**Given** "Back up now" **When** the user picks a location in the system "Save to" picker (`CreateDocument`, default name
`masroof-2026-10-27.masroof`) **Then** the file is written there, Settings shows "Last backup: today 14:05, 1,240
entries, 37 receipts, 58 MB", and no network permission is used by the app for this (the provider, e.g. Drive, uploads).
Unhappy path: **Given** the user cancels the picker **Then** nothing is written and the last-backup line is unchanged.
Unhappy path: **Given** the provider throws during the write (storage full, Drive offline without cache) **Then** the
user sees "Backup failed - the file may be incomplete, please delete it" with the reason, the last-backup line is not
updated, and the app's data is unchanged.
Unhappy path: **Given** the user leaves the app during the write **Then** the write continues in a foreground task with a
progress notification and finishes, or fails as above; it never reports success for a partial file.

## UX states (UI stories only)
- Settings "Backup" section: never backed up (warning "No backup yet - if this phone is lost your data is gone"), last
  backup line, running (progress %, Cancel), success, failure.
- loading: progress while deriving the key (about a second) and writing.
- offline: works offline (local providers); Drive handles its own upload.
- RTL / dark: strings in `values` + `values-ar`, passphrase fields LTR-safe for mixed text, 48 dp targets; the
  passphrase screen is `FLAG_SECURE`.

## Contract impact
None.

## Data / migration impact
None (reads all tables). New dependency `org.bouncycastle:bcprov-jdk18on` (ADR-0009 D5) with lockfile, verification
metadata and osv scan updated.

## Security / privacy / LLM-safety impact
Trust boundary L3 (app -> backup file): confidentiality and integrity per ADR-0009 D5; exclusions (review text, OTPs,
tokens) proven by the plaintext grep test; the passphrase is never persisted. Residual risks per ADR-0009 D5 "Not
covered" are stated in the UI warning. security-reviewer sign-off (ADR-0009 L3).

## Eval impact
None.

## Design notes (architect)
ADR-0009 D5. Budgets are added to the D5 file list (`data/budgets.jsonl`) because they are in the MVP.

## Dependencies
STORY-174 (receipts), STORY-181 (budgets table)

## Definition of Ready checklist
- [x] Goal + Part I links
- [x] Numbered REQs with Given/When/Then incl. unhappy paths
- [x] UX states (UI stories)
- [x] Contract impact
- [x] Data / migration impact
- [x] Security / privacy / LLM-safety impact
- [x] Eval impact
- [x] Size ≤ 400 changed lines (estimate M: header/KDF/stream ≈ 110, ZIP writer ≈ 70, UI ≈ 60, tests ≈ 160)
- [x] Dependencies listed

## Status log
| date | stage | agent | result |
|---|---|---|---|
| 2026-10-27 | 1 spec | product-analyst | READY - EPIC-17 local-only pivot (ADR-0009 D5) |
