# STORY-183 security review (security-reviewer, fresh context) - ADR-0009 L3 sign-off

## Round 1 - head `951ac8a` - CHANGES_REQUESTED
Commands: `bash scripts/make/check-fast.sh android` -> GREEN in 42s; backup suites 60 tests green; `bash scripts/make/security.sh`
-> osv-scanner no issues (bcprov-jdk18on 1.85), trivy fs/config OK, semgrep not installed locally, gitleaks full history
2 hits (known false positive: the AWS documentation example key in an unmerged backend SigV4 test).
Sound: the 39-byte D5 header is the Tink AES256_GCM_HKDF_1MB associated data; Argon2id v1.3 m=64 MiB t=3 p=1, 32-byte
output, 16-byte SecureRandom salt, NFC passphrase with its byte copy zeroed, RFC 9106 vector; the raw key imported
through Tink's documented API; only Argon2BytesGenerator/Argon2Parameters from Bouncy Castle, no provider registered;
the passphrase never in a Bundle/SavedStateHandle/Intent/WorkManager Data, the derived key handed over in memory and
zeroed; FLAG_SECURE and password fields; no logging; review_item/captured_tx/outboxes excluded with a table guard and
a plaintext grep test; receipts streamed without temp copies, path escapes treated as missing; merge-only manifest
change pinned by test.
FINDINGS:
- [MAJOR] RoomBackupDatabase.kt:15,25 -- template SQL (`masroof-no-template-sql-kotlin`, required by PR gate)
- [MINOR] BackupViewModel.kt -- no catch: a derivation failure crashed the app and the key was not zeroed
- [MINOR] BackupWorker/BackupState -- pending key put before enqueueUniqueWork(KEEP): keys could swap or leak
- [MINOR] RoomBackupDatabase.kt `SELECT *` -- no column pin for included tables
- [NIT] Tink key copy and passphrase String cannot be zeroed (residual); manifest test on source only; partial file
STATUS: CHANGES_REQUESTED

## Round 2 - `951ac8a..e5114a6` - APPROVE
All round-1 findings fixed. New staging step reviewed as new crypto and storage: per-table temp files encrypted with
the same Tink streaming AEAD under a random 32-byte in-memory key (zeroed), every segment authenticated so a tampered
or truncated temp fails before reaching the provider, only INCLUDED tables staged with pinned columns, app-private and
backup-excluded storage; queries via SupportSQLiteQueryBuilder; ViewModel catches Throwable (rethrows cancellation)
and zeroes the key; pending key per work-request id, second backup refused; acknowledge() no longer prunes all work;
`kept_apart_pairs`/`merged_away` (ids only) fine IN; session, keyset, device id, server URL, capture key, erase stage OUT.
FINDINGS:
- [MINOR] no tests for the staging controls (temp folder empty, no plaintext, tampered temp fails)
- [MINOR] stale stage files after a kill not swept, also not by Erase all data
- [NIT] table name not used as associated data; start() check-then-enqueue not atomic; stream not closed on a Tink
  header failure; discard skipped on cancel
THREAT_MODEL_UPDATED: L3 gains the staging step (per-table temp files under a lost-on-exit key, authenticated,
app-private; residual: ciphertext left after a kill, swept at the next run and by Erase all data).
STATUS: APPROVE (all MINORs and NITs fixed in `ee74d60`, diff reviewed by the orchestrator)
