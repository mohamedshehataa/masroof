# STORY-183 verify report (qa-verifier, fresh context each loop)

## Loop 1 - base origin/main `22b2146`, head `951ac8a` - FAIL
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 136s; `./gradlew :app:testDebugUnitTest --rerun` -> 145 suites,
1443 tests, 0 failures, 0 skipped (backup suites: Contents 13, Crypto 13, Manifest 2, SectionScreen 9, Streaming 1 in
9.36 s, ViewModel 10, Worker 5, Writer 7). TEST_INTEGRITY: OK (0 -> 218). Merged manifest inspected. Probe of 8 cases.
- REQ-SEC-066: PASS - the real header is the exact 39-byte ADR-0009 D5 layout (MASROOFB, 1, 1, 65536 KiB, t 3, p 1,
  16-byte salt at 20..35, aeadId 1, reserved 0); decrypts with Tink's predefined AES256_GCM_HKDF_1MB and the header as
  associated data, fails with empty AD; 39/39 header bit flips, body flips, cuts of 1/100 bytes and at a segment
  boundary, appends of 1 byte or 1 MiB all fail; RFC 9106 vector; salts and ciphertexts differ; plaintext grep clean
- REQ-SEC-067: PASS - explanation + checkbox; empty / 11 code points / mismatch keep Continue off with the reason;
  `abcdefghij😀` (11 code points) TooShort; NFC equivalence incl. 12 x decomposed e-acute and Arabic NFD; FLAG_SECURE on
  the activity and the dialog window; no passphrase logging or storage
- REQ-OPS-176: PASS at this head - all 20 included and 4 excluded tables filled and compared column for column;
  manifest, amounts, instants, missing receipt message, local-id key; 500 MB under -Xmx48m in 9.36 s
- REQ-OPS-177: PASS at unit level - success, ENOSPC mid-write (STORAGE_FULL, last-backup unchanged), CANNOT_OPEN,
  KEY_LOST, failure on close, cancel, picker cancel; merged manifest: SystemForegroundService exported=false,
  dataSync; only FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC added
FINDINGS:
- [MAJOR] BackupPrefsInventory.kt:41-55 (+ BackupContentsTest.kt:705) -- no guard for pref keys or files on neither
  list (the settings test compares against the same allowlisted snapshot); STORY-173 kept_apart_pairs/merged_away and
  STORY-186 masroof_backfill would be silently left out -- probe: keys written, backed up, absent, no test fails
- [MINOR] RoomBackupDatabase.kt:13 + BackupWriter.kt:147-159 -- the snapshot holds Room's write lock while compressed
  rows stream to the provider; a slow provider blocks SMS capture (traced)
- [MINOR] BackupViewModel.kt:114-119 -- process death while the picker is open: the picked file is ignored silently
  and an empty file is left (traced)
- [NIT] invalid receipt file name fails the whole backup; ids r/1 and r1 collide in the ZIP; a lone surrogate becomes
  `?`; 12 spaces accepted as a passphrase; acknowledge() prunes all finished work; "12" in Western digits in Arabic
- [NIT, STORY-184] restore must check the KDF bounds before deriving the key (unbounded header flips took 418 s)
STATUS: FAIL

## Loop 2 - base origin/main `439ec0f`, head `e5114a6` - PASS
Gate: `bash scripts/make/check-fast.sh android` -> GREEN in 53s; `./gradlew :app:testDebugUnitTest --rerun` -> 157 suites,
1565 tests, 0 failures, 0 skipped (backup suites: Contents 13, Crypto 13, FixRound 7, Manifest 3, PrefsGuard 3,
SectionScreen 11, Streaming 1, ViewModel 14, Worker 7, Writer 7). TEST_INTEGRITY: OK (0 -> 272). osv-scanner: no issues
(168 packages incl. bcprov 1.85). Loop-1 crypto and passphrase results re-run: unchanged.
- REQ-SEC-066: PASS - no template SQL in android main (semgrep regex: 0 matches); BackupCryptoTest 13/13
- REQ-SEC-067: PASS - 12 x NBSP / U+3000 refused, (e+U+0301) x 6 TooShort(6), 11 emoji TooShort, 12 emoji accepted
- REQ-OPS-176: PASS - the pref guard fails on a new key (`zz_probe`) and a new prefs file (`zz_probe_file`);
  kept_apart_pairs and merged_away round-trip exactly through a real backup; 500 MB in a 48 MB heap
- REQ-OPS-177: PASS - real file-based Room, 3,000 expenses, provider sleeping 40 ms per write: an insert after the
  first provider byte took 0 ms and the late row is not in the backup; stage files hold no plaintext marker; 0 stage
  files after success, provider failure and cancel
FINDINGS:
- [MINOR] BackupPrefsGuardTest.kt:66-73 -- key names compared globally: a new settings key reusing another store's
  allowed name (`added`) passes the guard but is dropped from settings.json
- [MINOR] PinnedColumns.kt:164 -- the column pin reads a hard-coded 10.json, so a v11 column would pass
- [MINOR] BackupViewModel onPicked / deriveAndStart -- discard deletes whatever URI the picker returned; an existing
  backup picked with Overwrite is deleted although nothing new was written
- [NIT] stale stage files after a crash are not swept (also not by Erase all data); launcher start() not atomic;
  12 x U+200B / U+FEFF accepted as a passphrase; a cancel during key derivation leaves an empty document
STATUS: PASS

## Fix round 1 (between loops) - head `e5114a6`
Pref guard over every key, prefs file and DataStore (IN/OUT lists with reasons); `kept_apart_pairs` and `merged_away`
IN; queries via SupportSQLiteQueryBuilder (no template SQL); the snapshot is staged into encrypted temp files before
the provider is written, so writers are never blocked; pending key per work-request id and a second backup refused;
ViewModel failures show "could not start" with the key zeroed; interrupted picker message; column pin; NUL receipt
names, colliding ids, lone surrogates, whitespace passphrase, digit setting in the reason, merged-manifest test.

## MINOR fixes - head `ee74d60` (diff reviewed by the orchestrator)
A picked document is deleted only when it is still 0 bytes (OpenableColumns.SIZE), so an existing backup chosen with
Overwrite is never lost ("Backup did not start; your existing file was not changed"), also on a cancel during key
derivation; the pref guard is per store (a key reusing another store's name fails) and every SharedPreferences file
needs an owner; the column pin reads the schema of the current DB_VERSION; staging tests (folder empty after success,
failure and cancel; no plaintext marker; a flipped or swapped temp file fails with nothing sent to the provider);
staging in noBackupFilesDir/backup-stage, swept at each start and by Erase all data; the table name is the temp
streams' associated data; start() under a Mutex with the request id checked after enqueue; invisible-only passphrases
refused. Builder gate: `check-fast GREEN [android] in 236s`; TEST_INTEGRITY OK (0 -> 315).
