# STORY-184 restore recovery: durable journal and state machine

Status: design for the loop-6 build of `story/STORY-184-restore-from-backup` (head `db91e59`), owner choice (a) of
ESCALATIONS LOOP-184. Implements REQ-OPS-178/179 and keeps REQ-SEC-068 unchanged. Refines ADR-0009 D5: the "marker
file" becomes the journal described here. The builder implements this note exactly, and the verifier tests against it.
Checks 1 to 7, the crafted-file defences, the round trip, the settings allowlist and the date helpers on the branch are
not changed by this note.

## 0. Decisions (the short version)
- **R1 Commit point.** A restore attempt has a generation `gen` (random non-zero `Int`). The attempt is committed if
  and only if `PRAGMA application_id == gen`. The Room transaction that replaces every table also writes that value,
  so the commit point is exactly one SQLite commit. Nothing else decides "committed".
- **R2 Two-slot journal.** `filesDir/restore-journal.json` replaces `restore-marker.json`. It has a `committed` slot (the
  last committed generation that has not finished) and a `pending` slot (the attempt about to commit). A new attempt
  only *adds* `pending`. `committed` changes only after the new transaction has committed (promotion), so a kill at any
  point before that commit leaves the earlier generation exactly as it was. This fixes the loop-5 bug.
- **R3 Hashes live in the journal.** These four hashes are computed in memory over the bytes checks 6 and 7 read:
  tables, settings, key and the receipts listing. At Confirm they are written into the journal from memory.
  `validated.json` is removed. Every byte applied after the commit is first checked against these hashes.
- **R4 One staging directory per generation.** It is `filesDir/restore-stage/g<hex gen>/`. A new pick never writes
  into, or deletes, the staging that a journal slot names.
- **R5 Before the commit, nothing outside staging and the journal changes. After the commit, everything moves
  forward only.** There is no rollback after the commit. The steps run in order: receipts swap, settings, key, cleanup.
  The journal records each one, and each one is idempotent.
- **R6 PARTIAL is decided only from evidence that cannot change by retrying.** That evidence is one of: a staged file
  missing or with a different hash, a journal that cannot be parsed, or an `application_id` that no slot explains.
  Everything else is INCOMPLETE and can be retried. The number of retries never changes the state; it only changes
  the wording of the message.
- **R7 An unreadable or unparseable journal counts as "something committed".** It is never deleted, except by a
  later commit that supersedes it or by Erase all data.
- **R8 A file-based gate.** `RestoreGate.blocked()` is true while the journal file exists or a restore runs. While it
  is blocked, intake is deferred and backups, receipt attaches, the purge and the backfill wait. Nothing is dropped.

## 1. Durable state

### 1.1 Objects on disk
| object | path | written by | atomic primitive |
|---|---|---|---|
| Room content + `application_id` (A) | `databases/masroof.db` (+WAL) | `RestoreDatabase.replaceAll` | one SQLite transaction, run with `PRAGMA synchronous = FULL` (set before `BEGIN` on the writer connection, previous value restored in `finally`), so the commit is durable against power loss before the call returns |
| journal (J) | `filesDir/restore-journal.json` | `RestoreJournal.write/delete` | write `restore-journal.json.tmp`, `fd.sync()`, `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`, then fsync `filesDir` (`FileChannel.open(dir, READ).force(true)`; an `IOException` from the directory fsync is swallowed only when `os.name` is not Linux, so tests on Windows run). Delete = `Files.deleteIfExists` + directory fsync. Writing a journal with both slots null is a delete. |
| staging of gen g (S_g) | `filesDir/restore-stage/g<8 hex>/` holding `table-<name>.jsonl`, `settings.json`, `local-id.key`, `receipts-new/<fileName>` | `BackupRestorer.stage` | every file `fd.sync()` after its last write, then fsync of `receipts-new/` and `g<hex>/`, all **before** Confirm is shown. Deleted with `deleteRecursively` after the key file has been overwritten with zeros. |
| receipts (R) | `filesDir/receipts/` | swap | `Files.move(ATOMIC_MOVE)` of a whole directory on the same file system, then fsync of `filesDir` |
| old receipts (O) | `filesDir/receipts.old/` | swap | rename `R -> O`; deleted in cleanup. After the commit, O is always garbage. |
| settings | DataStore `settings`/`answers`/`subscriptions` + SharedPreferences on the IN list | `RestoreSettingsApplier.apply` | DataStore `edit` (tmp + sync + rename inside DataStore); SharedPreferences `commit()`. Idempotent: every allowlisted key is set or removed. |
| local-id key | sealed under this phone's Keystore (today `masroof_capture_key` via `KeystoreLocalIdKey`; after STORY-182 `k_local` under `LocalKeystore`) | `LocalIdKeyStore.install` | seal, commit, read back, compare (the old or the new key is fully in place at every instant) |
| legacy marker | `filesDir/restore-marker.json` (loop 1-5 dev builds only) | never written again | read only as "corrupt journal" (R7) |

### 1.2 The journal record (version 2, strict parse)
```json
{ "v": 2,
  "committed": null
             | { "unknown": true }
             | { "gen": 123, "step": "COMMITTED|SWAPPING|SWAPPED|SETTINGS_DONE|KEY_DONE", "attempts": 0,
                 "tables": "<64 hex>", "settings": "<64 hex>", "key": "<64 hex>", "receipts": "<64 hex>" },
  "pending":   null
             | { "gen": 456, "from": 123,
                 "tables": "<64 hex>", "settings": "<64 hex>", "key": "<64 hex>", "receipts": "<64 hex>" } }
```
- `from` is the `application_id` read under the lock just before the pending slot was written.
- A file is **Corrupt** if `v != 2`, the JSON does not parse, a field is missing or of the wrong type, a hash is not 64
  lowercase hex, `gen == 0`, `pending.gen == committed.gen`, or `step` is unknown. It is **Unreadable** when reading
  it throws `IOException`. The `.tmp` file is never read.
- Hash definitions (lowercase hex SHA-256):
  - `tables` = SHA-256 of `"<table>:<sha256 of the file bytes>\n"` for each staged table, sorted by table name. The
    bytes are those check 6 read (the validator reads through a `DigestInputStream`).
  - `settings` = SHA-256 of the bytes `RestoreSettingKeys.parse` was given in check 6.
  - `key` = SHA-256 of the 32 bytes `RestoreRows.keyOk` read.
  - `receipts` = SHA-256 of `"<fileName>:<sha256>\n"` for each `StagedReceipt`, sorted by `fileName`. The `sha256`
    values are the ones check 7 matched against the manifest; `receipts-new/` must contain exactly these names.

### 1.3 What is on disk at each point (one attempt X over a base whose committed slot is C or empty)
| point | A | J | S_X | S_C | R / O | settings, key |
|---|---|---|---|---|---|---|
| Staged (Confirm shown) | base | base `{C, null}` or absent | complete, fsynced, not referenced | as before | base | base |
| after the pending write | base | `{C, P=X from=base}` | complete | as before | base | base |
| inside the transaction | base (uncommitted) | same | same | same | base | base |
| **commit** | **X** | same | same | same | base | base |
| after promotion | X | `{X step COMMITTED, null}` | same | deleted next | base | base |
| SWAPPING written | X | step SWAPPING | same | gone | base | base |
| between renames | X | step SWAPPING | N present | - | R absent, O = old | base |
| after `N -> R` | X | step SWAPPING | N gone | - | R = X's, O = old | base |
| SWAPPED | X | step SWAPPED | settings + key | - | R = X's | base |
| SETTINGS_DONE | X | step SETTINGS_DONE | key | - | R = X's | settings = X's |
| KEY_DONE | X | step KEY_DONE | (cleanup next) | - | R = X's | both X's |
| Done | X | absent | absent | - | R = X's, no O | both X's |

## 2. States × events

The **session** (in memory: one file being picked, checked or confirmed) and the **durable state** (the journal plus
A) are two separate machines. A session never reads or writes the journal, and never touches any S_g other than its
own, until Confirm. That is why a re-pick, a wrong passphrase, a failed check or a Cancel can never change an
unfinished restore. This is the fix for the MINOR "a re-pick turns INCOMPLETE into PARTIAL".

### 2.1 Session states (RestoreCoordinator, memory only)
Idle · NeedsPassphrase · Checking(X) · Staged(X) · Restoring · Done · Failed(f). X is the session's generation. S_X
is created when Checking starts and is unreferenced until Confirm.

| state | event | next | user sees | guaranteed about the phone's data |
|---|---|---|---|---|
| Idle | picker cancelled (`uri == null`) | Idle | nothing | nothing read |
| Idle / Failed / Done | pick (any durable state) | header read: NeedsPassphrase; checks 1-2 fail: Failed(NOT_A_BACKUP / NEWER_VERSION / CANNOT_OPEN); read error: Failed(CANNOT_READ) | passphrase dialog or the message | only the header was read; durable state untouched |
| NeedsPassphrase | Cancel | Idle | Settings | nothing read beyond the header |
| NeedsPassphrase | pick again | as the "pick" row above | | same |
| NeedsPassphrase | passphrase entered | waits for the start-recovery job, then: a backup or backfill running gives Failed(BUSY); otherwise Checking(X) with X fresh (non-zero, not A, not a journal gen, no existing `g<hex>`) and S_X created | "Checking the backup..." | durable state untouched |
| Checking(X) | check 3 fails (wrong passphrase or any damage) | Failed(WRONG_PASSPHRASE_OR_DAMAGED), S_X deleted | the one message | nothing changed; durable state untouched |
| Checking(X) | checks 4-7 fail / OOM / provider read error / disk full | Failed(INVALID_CONTENT / LOW_MEMORY / CANNOT_READ / STORAGE_FULL(needs X MB)), S_X deleted | the message | same |
| Checking(X) | checks pass | Staged(X): S_X fsynced, hashes H_X in memory | confirmation with date and counts | same |
| Checking(X) | Cancel | Idle; job cancelled and joined, then S_X deleted | Settings | same |
| Checking(X) / Restoring | pick | ignored (button disabled; coordinator returns) | - | - |
| Checking(X) / Staged(X) | kill / power loss | (next start) Idle; S_X swept at start | Settings, plus the banner if a durable restore is unfinished | same |
| Staged(X) | Cancel | Idle, S_X deleted | Settings | same |
| Staged(X) | pick | S_X deleted, then the "pick" row | | same |
| Staged(X) | Confirm | Restoring, then `apply` (§4.4) gives Done / Failed(FAILED) / Failed(INCOMPLETE) / Failed(PARTIAL) | "Restoring...", then Home (Done) or the message | §2.2 |
| Staged(X) | S_X file missing or changed, or Erase ran, before Confirm | on Confirm: Failed(FAILED), S_X deleted | "The restore could not finish. Nothing was changed." (plus the banner if a durable restore is still unfinished) | nothing changed |
| Restoring | Cancel / Back / leaving the screen | ignored; the restore runs in the app scope under NonCancellable | progress | §2.2 |
| Done / Failed | dismiss | Idle | | |

### 2.2 Durable states (journal + A) and their events
Durable states:
- **CLEAN**: no journal.
- **APPLYING(X)**: `pending = X`, `A != X`, and the transaction is running in this process.
- **COMMITTED(X)**: `A == X`, and either `pending = X` (not yet promoted) or `committed = X` with step COMMITTED.
- **SWAPPING / SWAPPED / SETTINGS_DONE / KEY_DONE (X)**: `committed = X` with that step and `A == X`.
- **INCOMPLETE(X, step, n)**: one of the committed states above, where the last finish attempt failed with a cause
  that can be retried. **INCOMPLETE(?)**: the journal is Unreadable.
- **PARTIAL**: `committed = X` whose staging fails verification, or `committed = unknown`, or the journal is Corrupt,
  or A matches no slot.
- **Done** is CLEAN reached by a finish.

| durable state | event | next | user sees | guaranteed about the data |
|---|---|---|---|---|
| CLEAN | app start | CLEAN; sweep the stage root, O and the tmp file | nothing | phone as it was |
| CLEAN / INCOMPLETE / PARTIAL (base B) | Confirm(X) | settle B (§4.3), verify S_X against H_X, write `{B.committed, pending X from A}`, then APPLYING(X) | Restoring | before the transaction only S_X and the journal change; B's committed slot and S_B are byte-identical |
| any base | Confirm: S_X fails verification (missing / differs / IO) | base; S_X deleted; Failed(FAILED) | "...Nothing was changed." (+ the base banner) | nothing changed |
| any base | Confirm: the pending write fails (disk full / IO) | base; S_X deleted, the tmp file removed | Failed(FAILED) | nothing changed. If the rename did land, the next decide sees `A == from` and drops it |
| APPLYING(X) | transaction fails (SQLITE_FULL, constraint, a table hash that differs on read, IO) or throws at COMMIT | A re-read: `A != X`, so the journal is rewritten to the base (best effort) and S_X deleted; base | Failed(FAILED) "Nothing was changed." | rolled back; base intact |
| APPLYING(X) | A cannot be read after the transaction | journal left as it is; INCOMPLETE(?) shown | Failed(INCOMPLETE) + banner | decided at the next start (row 5 or 7-10 of §4.2) |
| APPLYING(X) | kill or power loss at any point before COMMIT returns | start: decide row 7/8/9/10 drops `pending`, deletes S_X, then base recovery (C finishes, or CLEAN) | base banner if C exists, else nothing | data before the attempt intact; C's slot and S_C untouched (**loop-5 fix**) |
| APPLYING(X) | commit returns | COMMITTED(X) | Restoring | Room = X, durable (synchronous FULL) |
| COMMITTED(X) | continue (in process) or app start / Finish | promote: `{X step COMMITTED, null}`, then delete S_C (or every S_g except S_X when C was unknown), then verify all (§4.5) | Restoring / nothing | Room = X; S_X intact |
| COMMITTED(X) | the promotion write fails | INCOMPLETE(X, COMMITTED, n+1) with the journal still `pending = X` | Failed(INCOMPLETE) + banner | the next decide promotes (row 5) |
| COMMITTED(X) | N, settings or key missing / hash differs / key not 32 bytes / settings parse fails | PARTIAL; no step runs | Failed(PARTIAL) + banner "Restore again" | Room = X; receipts, settings and key are the base's; the user is told; journal and S_X kept |
| COMMITTED(X) | a staged file cannot be read (IOException on an existing file) | INCOMPLETE(X, COMMITTED, n+1) | Failed(INCOMPLETE) + banner "Finish" | nothing after the commit applied; S_X kept |
| COMMITTED -> SWAPPING | journal step SWAPPING written | SWAPPING | | the renames come next |
| SWAPPING(X) | swap (§4.6) succeeds | SWAPPED | | R = X's receipts |
| SWAPPING(X) | a rename throws | INCOMPLETE(X, SWAPPING, n+1) | message + banner | the swap is idempotent from every directory layout |
| SWAPPING(X) | N and R both absent | PARTIAL (an empty R is created so the app works) | "Restore again" | Room = X, receipts lost only by tampering; user told |
| any finishing state | kill / power loss at any boundary | start: decide row 9, finish resumes at the recorded step | banner while it runs; nothing after it finishes (Done toast in Settings) | each step is idempotent, and the journal never goes back a step |
| SWAPPED(X) | settings apply throws (disk full / IO) | INCOMPLETE(X, SWAPPED, n+1) | message + banner | settings may be half written; the next finish rewrites every allowlisted key |
| SETTINGS_DONE(X) | key install throws (Keystore failure) | INCOMPLETE(X, SETTINGS_DONE, n+1) | message + banner | the old key or X's key, never a torn key; intake still deferred |
| KEY_DONE(X) | cleanup (zero the key file, delete S_X, delete O, delete the journal) | CLEAN = Done | Done -> Home | X fully applied; no plaintext key file left |
| INCOMPLETE(X, s, n) | app start / Finish (banner) | the finish from s gives Done / INCOMPLETE(n+1) / PARTIAL | for n >= 3 the message says "still can't finish; try again later or restore the file again" | never deletes S_X or the journal; never becomes PARTIAL by counting |
| INCOMPLETE(?) (Unreadable) | app start / Finish | re-read; nothing touched | message + banner "Finish" | nothing deleted |
| INCOMPLETE / PARTIAL | pick, wrong passphrase, failed check, Cancel | session only | the base banner stays | journal and S_C byte-identical (**MINOR fix**) |
| PARTIAL | app start / Finish | re-evaluated from the files (same result unless the files were fixed) | banner "Restore again" (opens Settings > Restore) | journal and staging kept |
| PARTIAL (Corrupt / unknown / unexplained A) or INCOMPLETE(?) (Unreadable) | Confirm(X) | the base becomes `committed = unknown`; on X's commit, promotion removes it and every other S_g | as Confirm | until X commits, still PARTIAL (conservative, R7) |
| any | Erase all data | CLEAN: journal + legacy marker deleted **first**, then the stage root, then O | Erase's own flow | everything gone, as Erase promises |
| any non-CLEAN | SMS / notification arrives | the intake job is DEFERRED (requeued, never counted as an attempt) | banner | nothing stored with the wrong key; recorded once after Done |
| any non-CLEAN | backup (manual or scheduled) | refused RESTORE_RUNNING | "Backup needs attention" (existing) | no backup of a half-restored phone |
| any non-CLEAN | receipt attach | Refused(RESTORE_PENDING) and the copied file deleted | "Finish the restore first" | no receipt written into a directory the swap replaces |
| any non-CLEAN | 30-day purge, Read past SMS (backfill) | skipped / paused without moving the cursor | banner | nothing hashed with the wrong key |
| any | Keystore failure outside the key step | not a restore event (the key step is the only Keystore use) | - | - |

Every non-CLEAN durable state has a way out other than Erase all data: INCOMPLETE through Finish or a new restore, PARTIAL
through a new restore of the same or another file. Every unhappy outcome is shown to the user and keeps the banner up.

## 3. Invariants (assert after every step)
Notation: A = `application_id`, J = parsed journal, `base` = the snapshot before the attempt (rows, receipt hashes, the
settings map, the key, plus any committed slot C), `target(X)` = the snapshot of an uninterrupted restore of X. The
checker reads the files and the fakes and needs no knowledge of the code path.

- **I1 Commit point.** Room rows equal `rows(base)` or `rows(target(X))`, and they equal the target exactly when `A == X`.
- **I2 Nothing unconfirmed.** If `A != X`: R holds exactly the receipts it held before (or C's progress), settings and
  key are not X's, and no O was created by X. A staging S_g is never applied unless J named g in a slot when A became g.
- **I3 The journal names every commit.** If `A == X` and X is not fully applied, then J names X (`pending.gen` or
  `committed.gen`), or J is Corrupt or Unreadable. A durable state where X is committed, unfinished and unnamed is a
  test failure (this is the loop-5 symptom).
- **I4 Recoverable or told.** If `J.committed = X` with step s and `A == X`, then every part needed for steps >= s is in
  S_X with hashes equal to J's (N for s <= SWAPPING unless already moved; settings for s <= SWAPPED; key for
  s <= SETTINGS_DONE). Otherwise the last outcome reported was PARTIAL and `pending` is PARTIAL.
- **I5 Earlier generation kept.** While `pending = X` and `A != X`, `J.committed` and every file of S_C are
  byte-identical to their state before Confirm(X).
- **I6 Monotone.** Across any sequence of recoveries, `committed.step` for a given gen never decreases, and `committed.gen`
  changes only to a gen equal to A.
- **I7 Gate.** `RestoreGate.blocked()` is true when the journal or the legacy marker exists or a restore runs. While it
  is true, no `expense_source` row with an SMS/PUSH/BACKFILL channel is inserted, and no backup, receipt attach or
  purge writes.
- **I8 Told.** After start recovery, `coordinator.pending` is not None exactly when the journal exists. It is Partial
  exactly when §4.2 decides PARTIAL or the finish found PARTIAL evidence.
- **I9 Hygiene.** After start recovery: every `restore-stage/g*` directory is named by a J slot (none when J is absent
  or Corrupt/Unreadable sweeps nothing); no `restore-journal.json.tmp`; when J is absent, no O. After Done: no
  `local-id.key` anywhere under `filesDir`.
- **I10 Bytes applied = bytes checked.** The table bytes inserted, the settings bytes parsed, the key bytes installed
  and the receipt files renamed into R each hash to the value in H_X / J (enforced by hashing on read; asserted by the
  tamper tests).
- **I11 Way out.** From every reachable durable state, with faults cleared, at most two user actions reach Done with
  phone = target: Finish, or pick the same file + passphrase + Restore.

## 4. Algorithms

### 4.1 Types
```kotlin
data class StageHashes(val tables: String, val settings: String, val key: String, val receipts: String)
enum class Step { COMMITTED, SWAPPING, SWAPPED, SETTINGS_DONE, KEY_DONE }
sealed interface CommittedSlot {
    data object Unknown : CommittedSlot
    data class Known(val gen: Int, val hashes: StageHashes, val step: Step, val attempts: Int) : CommittedSlot
}
data class PendingSlot(val gen: Int, val from: Int, val hashes: StageHashes)
data class Journal(val committed: CommittedSlot?, val pending: PendingSlot?)
sealed interface JournalRead { data object Absent; data object Unreadable; data object Corrupt; data class Ok(val j: Journal) }
// Absent = neither restore-journal.json nor restore-marker.json; legacy marker alone = Corrupt.

sealed interface Decision {
    data object Clean : Decision                                               // delete J (if present)
    data class Promote(val p: PendingSlot) : Decision                          // then Finish
    data class Finish(val c: CommittedSlot.Known, val dropPending: PendingSlot?) : Decision
    data class Partial(val dropPending: PendingSlot?, val keepAsUnknown: Boolean) : Decision
    data object Unreadable : Decision                                          // touch nothing
}
class StagedRestore(val gen: Int, val dir: File, val info: RestoreInfo, val validated: ValidatedBackup, val hashes: StageHashes, val epoch: Long)
```

### 4.2 `decide(read, A)`: a pure function and the test oracle
| # | journal read | condition | decision |
|---|---|---|---|
| 1 | Absent | - | Clean |
| 2 | Unreadable | - | Unreadable |
| 3 | Corrupt | - | Partial(drop = null, keepAsUnknown = true) |
| 4 | Ok(null, null) | - | Clean |
| 5 | Ok(_, P) | A == P.gen | Promote(P) |
| 6 | Ok(_, P) | A != P.gen and A != P.from | Partial(drop = null, keepAsUnknown = true) (unexplained) |
| 7 | Ok(null, P?) | P null or A == P.from | Clean after dropping P |
| 8 | Ok(Unknown, P?) | P null or A == P.from | Partial(drop = P, keepAsUnknown = true) |
| 9 | Ok(Known C, P?) | (P null or A == P.from) and A == C.gen | Finish(C, drop = P) |
| 10 | Ok(Known C, P?) | (P null or A == P.from) and A != C.gen | Partial(drop = P, keepAsUnknown = false) |

### 4.3 Bookkeeping (`settle`): shared by recovery and Confirm, under the erase lock
```
fun settle(d: Decision): Journal? =            // returns the journal that is now on disk; null = CLEAN
  when (d) {
    Clean        -> { val old = readJournalOrNull(); journal.delete(); old?.pending?.let { deleteStage(it.gen) }; null }
    Promote(p)   -> { val old = readJournalOrNull()
                      val c = Known(p.gen, p.hashes, COMMITTED, attempts = 0)
                      journal.write(Journal(c, null))                  // durable before anything is deleted
                      when (val oc = old?.committed) { is Known -> deleteStage(oc.gen); Unknown -> deleteStagesExcept(p.gen); null -> {} }
                      deleteLegacyMarker(); Journal(c, null) }
    Finish(c, p) -> { if (p != null) { journal.write(Journal(c, null)); deleteStage(p.gen) }; Journal(c, null) }
    Partial(p, u)-> { if (p != null) { val j = readJournal().j; journal.write(j.copy(pending = null)); deleteStage(p.gen) }
                      current journal (unchanged otherwise) }
    Unreadable   -> throw Incomplete                                     // nothing touched
  }
```
A write failure inside `settle` leaves the old file in place, which is still a valid input to `decide`, so the result
is INCOMPLETE and the next run retries.

### 4.4 Confirm (`RestoreFinisher.apply`), under the erase lock with `RestoreActivity.begin()`
```
fun apply(s: StagedRestore): Outcome {
  if (eraseLock.epoch != s.epoch) { deleteStage(s.gen); return FAILED }
  val A0 = db.applicationId()
  // 1. Settle the base so it holds no pending slot of its own.
  val read = journal.read()
  val base: CommittedSlot? = when (read) {
      Absent -> null
      Unreadable, Corrupt -> Unknown
      is Ok -> when (val d = decide(read, A0)) {
          Clean -> { settle(d); null }
          is Promote -> settle(d).committed            // a committed attempt whose promotion failed earlier
          is Finish -> settle(d).committed             // C is superseded only if X commits; it is NOT finished here
          is Partial -> { settle(d); if (d.keepAsUnknown) Unknown else read.j.committed }
          Unreadable -> Unknown
      } }
  // 2. The staging must still be exactly what was checked (hashes from memory, never from disk).
  if (verify(S_X: settings, key, receipts-new vs s.hashes) != MATCH) { deleteStage(s.gen); return FAILED }
  if (s.gen == A0 || s.gen == (base as? Known)?.gen) { deleteStage(s.gen); return FAILED }
  // 3. Name the attempt durably BEFORE the transaction; the committed slot is copied verbatim.
  try { journal.write(Journal(base, PendingSlot(s.gen, A0, s.hashes))) }
  catch (e: IOException) { journal.removeTmp(); deleteStage(s.gen); return FAILED }
  hooks.after(PENDING_WRITTEN)
  // 4. The commit point.
  try { db.replaceAll(s.gen, s.validated.tables, s.hashes.tables) { hooks.beforeCommit() } }
  catch (k: RestoreKilled) { throw k }
  catch (e: Throwable) { /* decided below */ }
  val A1 = try { db.applicationId() } catch (e: Throwable) { return INCOMPLETE }      // leave J for decide
  if (A1 != s.gen) {                                                                   // rolled back
      runCatching { journal.write(Journal(base, null)) }; deleteStage(s.gen)
      return FAILED                         // "Nothing was changed."; the base banner (if any) stays up
  }
  hooks.afterCommit()
  // 5. Promote and finish.
  val c = settle(Promote(PendingSlot(s.gen, A0, s.hashes))) ?: return INCOMPLETE    // a write failure -> INCOMPLETE
  return finish(c.committed as Known)
}
```
`replaceAll(gen, tables, expectedTablesSha, beforeCommit)`:
1. Set `PRAGMA synchronous = FULL` on the writer, then open the transaction with `PRAGMA defer_foreign_keys = ON`.
2. Delete every table in INCLUDED + EXCLUDED.
3. For each table file, sorted by name, insert every line, reading through a `DigestInputStream`.
4. Compute the `tables` hash over the bytes read. If it differs from `expectedTablesSha`, throw (rollback).
5. `PRAGMA application_id = gen`, then `beforeCommit()`, then commit.
6. In `finally`, restore the previous `synchronous` value.

### 4.5 `finish(c)`: idempotent, resumes at `c.step`, under the erase lock, NonCancellable
```
fun finish(c: Known): Outcome {
  val dir = stageDir(c.gen); val N = dir/"receipts-new"
  try {
    // Verify every part still needed, BEFORE any step (PARTIAL means: no further step of c was applied).
    if (c.step == COMMITTED) need(verifyDir(N, c.hashes.receipts))     // N must exist
    if (c.step == SWAPPING && N.exists()) need(verifyDir(N, c.hashes.receipts))
    if (c.step == SWAPPING && !N.exists() && !R.exists()) { R.mkdirs(); return partial() }
    val settingsBytes = if (c.step <= SWAPPED)       need(readVerified(dir/"settings.json", c.hashes.settings)) else null
    val keyBytes      = if (c.step <= SETTINGS_DONE) need(readVerified(dir/"local-id.key", c.hashes.key))   else null
    var s = c.step
    if (s == COMMITTED)     { s = journal.step(c, SWAPPING) }
    if (s == SWAPPING)      { swap(N); s = journal.step(c, SWAPPED) }
    if (s == SWAPPED)       { settings.apply(RestoreSettingKeys.parse(settingsBytes!!.decodeToString())); s = journal.step(c, SETTINGS_DONE) }
    if (s == SETTINGS_DONE) { require(keyBytes!!.size == 32 && keyBytes.any { it != 0 }); keys.install(keyBytes); s = journal.step(c, KEY_DONE) }
    keyBytes?.fill(0)
    // KEY_DONE: cleanup; each part is idempotent, and the journal goes last.
    zeroFile(dir/"local-id.key"); deleteStage(c.gen); O.deleteRecursively(); fsyncDir(filesDir); journal.delete()
    return DONE
  } catch (e: Partial)  { return partial() }                               // MISSING / DIFFERS / parse / bad key bytes
    catch (e: RestoreKilled) { throw e }
    catch (e: Throwable)  { runCatching { journal.bumpAttempts(c.gen) }; return INCOMPLETE }   // re-reads J, keeps its step, attempts + 1
}
readVerified(f, sha): if (!f.isFile) MISSING -> throw Partial; bytes = f.readBytes() (IOException -> INCOMPLETE); sha256(bytes) != sha -> throw Partial; return bytes
verifyDir(N, sha):    if (!N.isDirectory) throw Partial; listing over sorted files (a subdirectory or an unexpected name -> Partial); IOException -> INCOMPLETE
```
The same bytes are hashed and then used, so there is no gap between a check and its use for settings or the key. A
missing file is decided by `isFile`, never by catching `FileNotFoundException`. That keeps loop 4's "missing file read
as transient" designed out.

### 4.6 Swap (step SWAPPING, idempotent from every directory layout)
```
fun swap(N) {
  if (N.exists()) {                       // verified just above
    if (R.exists()) { O.deleteRecursively(); hooks(DELETE_O); move(R, O); fsyncDir(filesDir) }
    hooks.betweenRenames()
    move(N, R); fsyncDir(filesDir)        // N exists and R does not: R is X's from here on
  }                                       // N absent with step SWAPPING: N -> R already happened
}
```
After the commit, O is always garbage (no rollback), so deleting a stale O is safe in every layout. `ReceiptStore`
creates R outside the lock with `mkdirs()`, so a stray empty R can appear between the renames. In that case R and N
both exist on resume, and the algorithm moves the stray R to O.

### 4.7 Recovery entry points (RestoreCoordinator)
```
recoverAtStart():          // MasroofApp.onCreate after eraser.recoverInterrupted(); erase lock taken on the calling thread
  read = journal.read(); A = db.applicationId()
  sweep(read): Absent -> delete restore-stage/*, O, journal tmp
               Ok(j)  -> delete restore-stage/* not in {j.committed.gen, j.pending.gen}, journal tmp
               Unreadable/Corrupt -> delete only the journal tmp
  outcome = run(decide(read, A))
  pending.value = toPending(outcome); if outcome is INCOMPLETE/PARTIAL -> state = Failed(...)

finishPending():           // the banner's Finish (Incomplete only); never sweeps unreferenced directories
  same as recoverAtStart() without the sweep; Done -> state = Done

run(d) = when (d) { Clean -> settle; DONE
                    Promote -> finish(settle(d).committed)
                    Finish  -> settle(d); finish(d.c)
                    Partial -> settle(d); PARTIAL
                    Unreadable -> INCOMPLETE }
```
- The coordinator keeps the `Job` from `recoverAtStart()`. `onPassphrase` and `onConfirm` `join()` it first, so a start
  sweep can never delete a session's S_X.
- `onPicked` does **not** run any recovery and never touches the journal.
- All three entry points run under `eraseLock.mutex`, inside `RestoreActivity.begin()/end()` (BUSY if it is already
  set), under `NonCancellable`, and never throw past the coordinator: every Throwable becomes INCOMPLETE.
- `PendingState = None | Incomplete(attempts) | Partial`. It is set after every outcome. Its initial value, before
  start recovery finishes, is `Incomplete(0)` when the journal or the legacy marker exists.

## 5. Intake, backup and other writers while a restore is pending
`RestoreGate.blocked() = RestoreActivity.isActive() || journalFile.exists() || legacyMarker.exists()`. This renames
`RestorePending.active()`. It is a file check, so it holds in a WorkManager process before the start recovery has
run. Each writer checks it **under `eraseLock`**, the same lock restore holds, so it sees the state before or after a
restore step, never in the middle of one.

| writer | while blocked |
|---|---|
| `SmsPipeline.accept` (SMS and notification intake) | returns `DEFERRED` before hashing the id; `intakeResult` requeues a fresh job after 30 min (already on the branch); never dropped, never counted as an attempt |
| `SmsBackfill.handle` / run (STORY-186, on main) | `Disposition.DEFERRED` ends the run **without** moving `lastId`/`lastDateMillis` (the message is read again later); a run that starts while blocked does not start and says "Finish the restore first"; `BackfillWork` returns `retry()` |
| `BackupWorker` | `failure(RESTORE_RUNNING)` (on the branch; switch to the gate) |
| `ReceiptStore.commit` | deletes the copied file, returns `Refused(Refusal.RESTORE_PENDING)` (new value; string "Finish the restore first" in `values` + `values-ar`). Also, unconditionally, a stored file that no longer exists under the lock gives `Failed` |
| `DeletedEntryPurge.purge` | returns 0 without reading the key or deleting anything; the daily job runs again |
| manual entry, edits, categories, budgets | allowed: they change rows of the restored data, which no later step touches (settings edited while INCOMPLETE are overwritten when the settings step runs, which is the replace-all the user confirmed) |

**When STORY-182 lands** (it moves the key into `ingest/LocalIdKey.kt` under `LocalKeystore`):
- Restore depends only on the port `fun interface LocalIdKeyStore { fun install(key: ByteArray) }` in `data/backup`.
  Its contract: the key is 32 bytes and not all zero, else `IllegalArgumentException`. The call is idempotent. On
  return the stored key reads back equal. On a throw, either the old key or the new key is fully in place. It keeps no
  reference to `key`.
- Before 182, `BackupModule` binds it to the branch's `KeystoreLocalIdKey(context).install` (session keyset; 182's
  `get()` later moves that entry to the local keyset).
- After 182, `LocalIdKey` gains `fun install(key: ByteArray)` and `KeystoreLocalIdKey` implements it as
  `@Synchronized { require(valid); seal(key); legacy.remove(LEGACY) }`. `BackupModule` binds
  `LocalIdKeyStore { localIdKey.install(it) }`, and `CaptureSecurity.installWith` is deleted.
- Whichever of 182/184 merges second does the rebinding. `LocalIdKeyStoreContractTest` runs the four contract clauses
  against a test AEAD and must pass on both bindings.
- Nothing else in this note changes: the gate, the steps and the journal do not depend on where the key is sealed.

## 6. Test plan (all JVM/Robolectric, tag the REQ ids)

### 6.1 `RestoreDecideTest` (REQ-OPS-179): the oracle for §4.2
Enumerate the journal read (Absent, Unreadable, Corrupt, Ok) × committed (null, Unknown, Known(g1) for each Step) ×
pending (null, P(g2, from = g1), P(g2, from = other)) × A ∈ {0, g1, g2, other}. Assert the decision against the table
in §4.2, written in the test as an independent `when`. Add the strict-parse cases: `v != 2`, a short hash, `gen = 0`,
`pending.gen == committed.gen`, an unknown step, a truncated file, an empty file, and a legacy marker alone. Each must
give Corrupt.

### 6.2 `RestoreCrashMatrixTest` (REQ-OPS-178/179): the generated path × fault test
- **Seam.** Every durable operation goes through `RestoreDisk` and the hooks:
  - `RestoreHooks.before(op: RestoreOp)` / `after(op)`, with `RestoreOp` = JOURNAL_WRITE, JOURNAL_DELETE, STAGE_DELETE,
    TX_COMMIT, DELETE_O, RENAME_R_TO_O, RENAME_N_TO_R, FSYNC_DIR, READ_STAGED, SETTINGS_APPLY, KEY_INSTALL.
  - The existing `beforeCommit/afterCommit/betweenRenames` stay as named calls, so the old tests still compile.
- **Scenarios.** Built once each, with one Argon2 derivation per scenario. Every run copies the prebuilt S_X and
  `StagedRestore`.
  - (a) wiped phone;
  - (b) a phone with data and receipts;
  - (c) re-restore over INCOMPLETE G1 at SETTINGS_DONE (Keystore failure);
  - (d) re-restore over PARTIAL G1 (key file deleted);
  - (e) re-restore over a Corrupt journal;
  - (f) a backup with zero receipts on a phone without a receipts directory.
- **Enumeration.** For each scenario, record the op sequence of a clean `apply`; call its length n. Then for each
  k in 1..n, inject each of these at op k:
  - **Kill before/after op k**: throw `RestoreKilled`. Then "restart": a new restorer and coordinator over the same
    db, files and fakes, followed by `recoverAtStart()`.
  - **IO at op k**: a read op throws `IOException`; a write op throws `IOException("ENOSPC")`, for disk full.
  - **Keystore failure**: only at KEY_INSTALL, where the fake throws.
  - **Corrupt or missing staged file**: at the boundary before op k, delete or flip one byte of each of settings, key,
    one receipt, and one table file.
  - **Double kill**: for every post-commit k1, kill again at every op k2 of the recovery run, then restart and
    recover clean.
- **After every injected step and every restart**, call `assertInvariants()` for I1-I10.
- **Then the final user loop** runs with faults cleared, for at most 2 iterations:
  - if `pending == Incomplete`: Finish;
  - if `Partial`, or CLEAN with the phone == base: pick the same file + passphrase + Restore.
  - It must end with I11: phone == target (rows column by column, receipt hashes, the applied settings map, the
    installed key), no journal, no `restore-stage`, no O, no key file, and the gate open.
- **Budget.** About 450 runs. If it runs over 60 s, tag it `@Tag("slow")` so it runs in `make check`, and keep
  scenario (c) in check-fast.

### 6.3 `RestoreDurabilityOrderTest` (REQ-OPS-179)
From the op log of a clean run, assert the order:
1. tmp fsync, rename, then directory fsync for every journal write;
2. the pending write before TX_COMMIT;
3. `PRAGMA synchronous` reads 2 inside `beforeCommit`;
4. the promotion after TX_COMMIT;
5. each step record after its effect;
6. JOURNAL_DELETE last.

Also assert that staged files are fsynced before Confirm is returned. Use a `RestoreDisk` spy for this.

### 6.4 Regression repros (one named test each)
| id | loop | test |
|---|---|---|
| R1 | 1 | Keystore fails at KEY_INSTALL: staging and journal kept (step SETTINGS_DONE); the next start installs the key; phone == target |
| R2 | 2 | G1 INCOMPLETE; stage G2 (not confirmed); kill; start: S_G2 swept, G1 finished from S_G1, no byte of G2 applied |
| R3 | 3 | settings.json edited after the commit: PARTIAL; no settings or key applied; banner Partial; re-pick completes |
| R4 | 3 | settings.json **and** every other staged file edited between Staged and Restore: refused FAILED, nothing changed; no `validated.json` exists at any point |
| R5 | 4 | `local-id.key` deleted after the commit: PARTIAL on the first recovery (not INCOMPLETE); re-pick completes |
| R6 | 5 | G1 INCOMPLETE (Keystore); re-pick G2 + Restore; kill (i) right after the pending write and (ii) in `beforeCommit`: next start shows J.committed == G1 unchanged, S_G1 intact, G1 then finishes (key, settings, receipts of G1), S_G2 gone, no silent state |
| R7 | MINOR | journal bytes `{` -> PARTIAL, journal and every `restore-stage/g*` kept, banner Partial, re-pick completes and removes them; `IOException` on read -> INCOMPLETE, nothing deleted |
| R8 | MINOR | G1 INCOMPLETE; re-pick then (i) wrong passphrase, (ii) Cancel at the passphrase, (iii) a failed check 6, (iv) Cancel at the confirmation: the journal file is byte-identical and S_G1 untouched after each; the next Finish completes G1 |
| R9 | new | power loss around the commit: `PRAGMA synchronous` reads 2 inside `beforeCommit` and is restored afterwards; a kill between the commit and the promotion (journal still `pending = X`, A == X) recovers to target through decide row 5; a transaction that throws at COMMIT is judged by re-reading A, never by the exception |
| R10 | 4 | an SMS arriving in INCOMPLETE is DEFERRED; after Finish it is recorded once with G's key; the same SMS recorded before the backup is not recorded twice |
| R11 | new | backfill during INCOMPLETE: the run stops at the first message, the cursor is unchanged; after Done it resumes and records each message once |
| R12 | new | apply over a Corrupt journal, killed before the commit: still PARTIAL (`committed = unknown` kept) |
| R13 | new | Erase all data in INCOMPLETE and in PARTIAL: the journal goes first (kill after it: the start sweeps the rest), no banner, the gate is open |
| R14 | new | a receipt attach in INCOMPLETE at step COMMITTED is refused, and after Finish R holds exactly the backup's receipts |

### 6.5 Existing branch tests to rewrite to this design (keep the REQ tags; these tests were never on `main`)
- `RestoreFixRound4Test`:
  - "an INCOMPLETE that fails on three starts in a row becomes PARTIAL" becomes "stays INCOMPLETE, the message
    escalates at 3 attempts, and Finish completes once the fault clears".
  - "a validated json deleted after the commit is PARTIAL" becomes "the receipts-new directory deleted after the commit
    is PARTIAL".
- `RestoreFixRoundTest` "while a restore marker exists a new pick first finishes the old restore" becomes "a new pick
  leaves the unfinished restore untouched until the new one commits" (R8).
- `RestoreFixRound2Test` "a Keystore failure during the recovery of a new pick..." becomes "...during Finish shows
  INCOMPLETE; a new restore then completes".
- Any test that reads `restore-marker.json`, `broken` or `failures` reads the journal instead.

## 7. Code changes, file by file (branch `story/STORY-184-restore-from-backup`, rebased on `main` first)
| file | keep | change | delete |
|---|---|---|---|
| `data/backup/BackupRestorer.kt` | `checkHeader`, `Tracking`, `stage` checks 1-7, `drain`, `extract`, `checkSpace`, `openStage`, `write`, `arrangeReceipts`, `CountingInput` | `stage` picks X and stages into `restore-stage/g<hex>/`. It computes `StageHashes` from the bytes checks 6/7 read and fsyncs files and directories. On failure it deletes **only** S_X. It returns `StagedRestore(gen, dir, info, validated, hashes, epoch)`. `discard(staged)` becomes `discardSession(gen)`, which deletes S_gen only. | lines 118-128 (marking a marker broken during stage); `writeValidated`, `tablesDigest`/`receiptsDigest` from disk, `stagingMatches`, `Staging`, `Marker`, `readMarker`, `writeMarker`, `hasMarker`, `markerBroken`, `apply`, `finishGuarded`, `finish`, `swapReceipts`, `returnOldReceipts`, `recover`, `pickNonce`, `MAX_FINISH_FAILURES` |
| `data/backup/RestoreJournal.kt` (new) | | the record of §1.2: strict parse, `read(): JournalRead`, `write`, `delete`, `step(c, s)` (writes and returns s), `bumpAttempts(gen)`, `removeTmp`, legacy marker read | |
| `data/backup/RestoreRecovery.kt` (new) | | `decide()` (pure, §4.2), `settle`, `finish`, `swap`, `apply`, `recover(atStart)`, `sweep`, `RestoreDisk` (fsync, move, delete, readVerified, verifyDir; every op goes through the hooks) | |
| `data/backup/RestoreDatabase.kt` | `schema`, `liveEntryCount`, `applicationId`, `values`, `known` | `replaceAll(gen, tables, expectedTablesSha, beforeCommit)`: synchronous FULL and hash-on-read (§4.4) | |
| `data/backup/RestoreValidator.kt` | everything | reads table, settings and key bytes through a `DigestInputStream` and returns their hashes in `ValidatedBackup` | |
| `data/backup/RestoreModel.kt` | `RestoreFailure`, `RestoreException`, `RestoreInfo`, `RestoreLimits`, `RestoreActivity`, `readHeaderBytes` | `RestoreHooks` gains `before/after(op)`; `RestoreEnvironment.keys: LocalIdKeyStore` and a `journal` path; `RestorePending` becomes `RestoreGate.blocked()` (journal or legacy marker or active); `RestoreStage` gains `JOURNAL`, `sweep()` deletes the journal + legacy marker **first**, then the stage root, then O | `LocalIdKeyInstaller` (renamed `LocalIdKeyStore`) |
| `data/backup/RestoreCoordinator.kt` | the flow, `RestoreSources`, `RestoreBlockers`, `onDismiss`, `cancelAndJoinQuietly` | `onPicked`: no recovery, deletes only the session's S_X. `onPassphrase`/`onConfirm` join the start job. `onConfirm` calls `apply`. `failed()` deletes only the session's S_X. `pending: StateFlow<PendingState>`. `finishPending` = recover(atStart = false). `recoverInterrupted` = recover(atStart = true) | `recoverGuarded`, the "a pick first finishes" branch |
| `data/backup/AndroidRestoreSettings.kt` | all (idempotent) | none | |
| `ingest/CaptureSecurity.kt` | `installWith` until STORY-182 merges | becomes the `LocalIdKeyStore` binding | deleted at 182 (§5) |
| `ingest/SmsWork.kt` | `DEFERRED`, `requeueDeferred`, `intakeResult` | `restorePending.active()` -> `gate.blocked()`; merge with main's `Disposition.KNOWN` | |
| `ingest/backfill/SmsBackfill.kt`, `BackfillWork.kt` (main) | | handle `DEFERRED` per §5; refuse to start while blocked | |
| `data/edit/DeletedEntryPurge.kt` (main) | | under the lock, return 0 when the gate is blocked | |
| `data/receipts/ReceiptStore.kt`, `ReceiptTypes.kt` + strings (main) | | `Refusal.RESTORE_PENDING`; the gate check and the file-exists check in `commit` | |
| `data/backup/BackupWorker.kt` | the refusal | uses the gate | |
| `data/auth/RoomDataWiper.kt` | the sweep call | relies on the new `RestoreStage.sweep` order | |
| `ui/settings/RestorePendingBanner.kt` | the layout | Incomplete: "Finish the restore" (calls `finishPending`); Partial: "Restore again" (opens Settings > Restore, no recovery call); new strings `restore_pending_partial`, `restore_err_incomplete_again`; `restore_err_incomplete` loses "close and reopen" ("Tap Finish the restore to try again."). Arabic in `values-ar` | |
| `di/BackupModule.kt` | | binds `LocalIdKeyStore`, `RestoreGate`, journal path | |
| tests | `RestoreTest` checks 1-7, round trip, the logging test, `RestoreCoordinatorTest` | §6.5 rewrites | marker-only assertions |

Size: about 350 production lines changed (more removed than added in `BackupRestorer`) and about 700 test lines.
The story was already far above 400 lines when LOOP-184 was decided. The owner chose option (a), which finishes it
on its branch, so this note does not split it.

## 8. Threats (STRIDE, L3 backup file -> app, and L5 at rest)
- **Tampering.** An attacker edits staging or the journal in app-private storage, which is possible only on a rooted
  or unlocked phone (L5 accepted risk).
  - Hashes are taken from memory into the journal, and every byte is hashed when it is read.
  - A mismatch is PARTIAL, never applied.
  - A forged journal can at most cause PARTIAL or apply a staging that has to match its own hashes, and that staging
    passed checks 1-7 only if the user confirmed it, because a generation's staging exists only after its checks.
- **Spoofing / elevation.** Unchanged: zip-slip allowlist, generated receipt names, and the canonical-path guard in
  `arrangeReceipts`.
- **Repudiation / rollback.** Restoring an old file stays a user choice made with its date shown (ADR-0009 L3).
- **Information disclosure.**
  - The raw local-id key exists as a file only in S_g. It is zeroed and deleted at cleanup, and kept only while a
    journal slot needs it, in app-private storage with `allowBackup=false`.
  - The journal holds SHA-256 values of a random 256-bit key and of the settings. They reveal nothing usable.
  - No log line carries a gen, a hash, a path under `restore-stage` or content. Only the outcome enum is logged, at
    INFO at most. Enforced by the existing restore logging test and semgrep `no-log-body`.
- **Denial of service.**
  - A corrupt journal keeps capture deferred until a new restore or Erase (conservative, user told).
  - In PARTIAL the receipts are re-hashed at every start (rare state; accepted).
  - Two stagings may be needed during a re-restore over an unfinished one. Disk full gives "Not enough space (needs X
    MB)" and nothing changes.
- **SMS between Checking and Done** is deferred, not recorded: `RestoreActivity` is held from the start of Checking
  through Staged and Restoring (see §9), so nothing lands in a database that is about to be replaced.
- **Known limitation.** In COMMITTED, SWAPPING and SWAPPED the base settings still filter which senders and apps are
  captured, so a deferred message from a sender the restored settings add is only read once the backfill runs; the
  backfill is the recovery path. Messages are deferred once they reach the pipeline; one that never reached it (a sender
  the base settings drop) is not.
- **Tampering** with a journal or a staging folder needs L5 write access to app-private storage, equivalent to writing
  the preferences or the receipts directly. Accepted risk.
- **security-reviewer sign-off is required.** This is L3, it handles the local-id key, and it defers ingestion.

## 9. Security amendments (stage-3 sign-off)
- **Key (CWE-703).** `LocalIdKeyStore` is bound only to STORY-182's no-mint `LocalIdKey` (`get()` throws `LocalIdKeyUnavailableException` and never replaces an entry; install uses `LocalKeystore`'s non-resetting AEAD), never to `TinkKeystore`/`KeystoreLocalIdKey`. Until 182 is on main the production binding is a `TODO(STORY-182)` stub that throws (the key step then ends INCOMPLETE).
- **Contract clause 4.** `LocalIdKeyStoreContractTest`: a transient `ProviderException`/`GeneralSecurityException` in `get()` or `install()` never replaces or deletes the stored key (an AEAD that fails once).
- **Gate before the key.** While the gate is blocked `SmsPipeline.accept` returns DEFERRED with zero `idKey.get()` calls (the check moved before the key read; it is repeated under the erase lock).
- **SMS lost (CWE-362).** `RestoreActivity` is held from the start of Checking through Staged and Restoring; it is released on Cancel, a failure, Done or a new pick (process death clears it). Intake is DEFERRED in that window and recorded once after Done with the restored key; Cancel in Staged records with the base key.
- **Unreadable journal at Confirm.** The read is retried once; if it still fails Confirm returns INCOMPLETE ("Finish the restore first") and writes nothing. Only a Corrupt journal may become `committed = unknown`.
- **Strict parse.** Exact JSON types (no coercion), at most 4 KiB read (else Corrupt), and the parsed value must re-encode to the identical text, which rejects unknown keys, duplicate keys and any other form; `attempts >= 0`; staging paths are built only from the parsed Int with `%08x`.
- **Symlinks.** `verifyDir` and `readVerified` accept only regular files (NOFOLLOW_LINKS) and re-hash every file against the listing hash; every delete is a non-following `walkFileTree`, and the key file is overwritten only when it is a regular file.
- **Second read.** `replaceAll` re-applies `maxRowChars` and `maxRowsPerTable` while it inserts and reads each file to EOF through the digest, so a line grown after Staged ends FAILED with nothing changed.
- **Entry cap.** `extract` counts ZIP entries and refuses more than `maxEntries` (MAX_RECEIPTS + tables + 3) while streaming.
- **Third way out of PARTIAL.** "Keep the restored data as it is" (with a warning that the current key and settings stay): allowed only when the journal is readable, names a Known committed slot and `A == C.gen`; it deletes the journal and the staging.
- **Blockers at Confirm.** `RestoreBlockers.busy()` is checked again under the lock (a backup or backfill that started after Staged makes Confirm BUSY); the ServerImporter joins the blockers when STORY-182 lands.
- **R8 wording.** Messages are deferred once they reach the pipeline (see the §8 known limitation).
- **Logging.** The crash matrix captures `ShadowLog` and asserts nothing restore-related is logged but an outcome, at INFO or below: no gen hex, hash, `restore-stage` path, row text or Throwable.
- **Deferred SMS bodies.** A deferred intake job keeps its SMS text in WorkManager input data (a fresh delayed job every 30 minutes, never counted as an attempt) until the restore is Done, the user keeps the data as it is, or Erase deletes it; there is no other bound.
- **Zeroing** on flash is best-effort (file-based encryption is the control); it runs in every `deleteStage` and sweep path, and the crash matrix asserts no `local-id.key` after every sweep (I9). No-op hooks are bound in release; `RestoreKilled` is only thrown by test hooks.

## 10. Implementation notes
- `RestoreHooks` keeps `beforeCommit/afterCommit/betweenRenames` and gains `before(op)/after(op)`; `RestoreActivity` stays a process flag and `RestoreGate.blocked()` ORs it with the journal file.
- `BackupWorker` cannot take the gate as a constructor argument (its main-branch tests build it positionally), so it checks `RestoreActivity.isActive() || RestoreStage.journalBlocks(...)` directly; the effect is the same.
- Erase deletes the journal at the START of `RoomDataWiper.wipe` (before the database is cleared), not only in the cleanup sweep: a kill between the database clear and the sweep must not leave a journal that resumes after Erase.
- `StagedRestore.epoch` is read from `EraseLock.epoch` through `RestoreEnvironment.epoch`.
- `finish` hashes settings and key with `readVerified` (regular file, hash equal to the journal's) and the receipts with `verifyDir`; a missing receipts folder is accepted only at step SWAPPING when `receipts/` already exists (the second rename happened).
- `RestoreCrashMatrixTest` runs about 4.5 minutes (six scenarios, every operation killed before/after or failing with a full disk, a second kill during recovery). It is a normal test today; tag it slow if CI time matters.
- Round-1 additions after the redesign (verify + security): `SmsPipeline.accept` reads the key only under the erase lock after the restore gate (both checks come before any key read, the backfill checks the gate before it hashes), and a DEFERRED result never marks a message handled. Persistent INCOMPLETE has a way out at the KEY step: after `KEY_STEP_ATTEMPTS = 3` failed attempts (counted in the journal) the banner also offers "Keep the restored data and the current key" (`keepCurrentKey`: journal and staging deleted, gate reopened, settings and receipts already applied, current key kept, with a warning that SMS captured meanwhile may be recorded twice). "Keep as it is" is shown only when it can act (`PendingState.Partial.canKeep`), and it moves every staged receipt that is a regular file and belongs to a restored receipt row into place before it deletes the staging. The Erase path (`RestoreStage.sweep/deleteJournal`) uses the non-following `RestoreDisk.deleteTree`/`deleteFile`, zeroes each key file, fsyncs the directory and the wipe transaction sets `application_id = 0`; `DataEraser` tells the coordinator (`RestoreErasedListener`) so the banner and session reset. The coordinator guards its session fields with one lock and a session id: Cancel during Checking never publishes Confirm and releases the restore flag at once, and Confirm refuses BUSY unless this session holds the flag. A failing directory fsync after the journal delete reports Done. The crash matrix is now one class per scenario (parallel forks, `maxParallelForks` up to 3) with damage to a staged file at every boundary, a second kill over all operations of every recovery, and invariants I4, I6 and I8 asserted (I10 is the comparison with the target). R14 second half: `ReceiptStore.commit` returns Failed when the stored copy no longer exists under the lock (no dedicated test: the file cannot be removed between the copy and the lock from outside).
