# STORY-182 local-id key availability policy: bounded escalation, decide table and fault matrix

Status: design for the loop-5 build of `story/STORY-182-one-time-server-import` (head `14f54ad`). It covers the part of
REQ-SEC-065 that loops 2-4 kept re-opening: what the app does after a Keystore or Tink failure while it reads the
local-id key `k`. Everything else on the branch is not changed by this note: the importer, the duplicate card, the
forget step and the HTTP caps. The builder implements this note exactly, and the verifier tests against it (§7).
This note supersedes the KDoc table in `ingest/LocalIdKey.kt` and two parts of the 2026-11-09 STORY-186 decision: the
`LocalKeystore` "provably gone" destroy, and `RecoveringAead.recoverIfLost` in production.

## 0. Decisions (the short version)
- **D1 Two kinds of evidence.** Once a keyset has *built*, its AEAD is plain Tink AES-GCM in software. The Android
  Keystore master key is only used to unwrap the keyset while it builds. So an entry that a freshly built keyset
  rejects (bad tag or bad Base64) is lost for certain. That rejection is acted on at once. Uncertainty exists only when
  the *build* fails, and only build failures go through escalation.
- **D2 Bounded escalation.** For each keyset (LOCAL, SESSION) a durable *episode* record keeps: when the failures
  started, how many were counted (at most one per minute), how many were CORRUPT, how many process starts saw them, and
  whether the loss is corroborated. A keyset is declared lost only when `lost(episode, now)` (§3.3) holds:
  - LOCAL: 2 CORRUPT parses; or, if corroborated, ≥ 3 counted failures over ≥ 1 h; otherwise ≥ 5 counted failures over
    ≥ 6 h seen by ≥ 2 process starts, or ≥ 5 over ≥ 24 h.
  - SESSION: 2 CORRUPT parses, or ≥ 3 counted failures over ≥ 1 h. SESSION is only built after LOCAL has built in the
    same call, so the Keystore is known to work: every SESSION failure is corroborated.
  - Any successful build ends that keyset's episode.
- **D3 What "lost" does.** LOCAL declared lost leads to `RecreateLocal`: the backfill memory is cleared, the local
  keyset and its master key are destroyed, and the probes restart. If the legacy key opens it is moved; otherwise a key
  is minted. SESSION declared lost leads to `DropLegacy`: the legacy entry `k` is removed and a key is minted. The
  policy never destroys the session keyset itself.
- **D4 `k_local` is authoritative.** Whenever `k_local` opens, the legacy entry `k` is redundant and is removed in the
  same call. A move removes `k` as soon as the read-back passes, no longer at the forget step. So "the session keyset
  still holds an unmoved key" is the durable predicate `prefs has "k"`.
- **D5 Session keyset safety (R6).** The resetting `TinkKeystore.aead` may be used only when `prefs has "k"` is false.
  While `k` exists, the session cipher uses the non-resetting builder. If that builder fails, it throws
  `SessionKeysetHeldException`, and `EncryptedSessionStore.load()` then returns `Empty` and **keeps the blob**.
- **D6 Nothing is dropped (R3).** While the key is unavailable, intake re-enqueues the same message as a fresh job
  (15 min delay, same tag and input) and ends the old job with success. The key cause never counts toward
  `MAX_INTAKE_ATTEMPTS`. Backfill retries without a cap for this cause. The purge does not run and retries.
- **D7 containsAlias never destroys.** `LocalKeystore.recoverIfLost` (alias missing means destroy) and
  `buildRecovering` (`isKeyLost` means destroy) are removed from the production build path. A missing alias, checked
  only after a failed build through the existing `needsRecovery` with `master_made`, can at most *corroborate* a LOCAL
  episode. That shortens the window to ≥ 3 failures over ≥ 1 h. It never destroys on its own.
- **D8 All decision state is durable (R4).** Inputs are the `masroof_capture_key` prefs (entries, episodes, notice flag),
  the keyset files and the clock. They are recomputed at every call. The per-process token only de-duplicates the
  start count. The `RecoveringAead` primitive cache is a performance cache, and a bad tag seen through a cached
  primitive is confirmed with a fresh build before it counts.
- **D9 Writes are checked.** `SharedKeyPrefs` writes with `commit()` and throws `IOException` when it returns `false`.
  Today the result is ignored, and the in-memory map makes a write that never reached disk "read back" fine. A
  storage write failure means Hold and is **not** counted as a Keystore failure.
- **D10 One-time note (R7).** When a mint replaces a key that existed (a `k_local` with a bad tag, or a dropped legacy
  `k`), the flag `renewed` is committed together with the new entry. The next successful `get()` removes the flag and
  then posts one notification (`key_renewed_title` / `key_renewed_text`). No note is shown on first install or after
  Erase.

R2 bound: with permanent loss, capture, backfill, backup and purge are held for at most **26 h** in the worst case
(LOCAL uncorroborated, the process never restarts, then SESSION also gone). The usual cases are ≤ 1 h 15 min
(corroborated loss of one keyset) and immediate (corrupt keyset, or bad tag under a built keyset). Erase all data is never needed.

## 1. Durable state

### 1.1 Objects
| object | where | written by | notes |
|---|---|---|---|
| `k_local` | prefs `masroof_capture_key` / `k_local` | MoveLegacy, Mint, install | Base64(Tink AEAD(k, AAD `masroof-local-id`)) under LOCAL |
| `k` (legacy) | same file / `k` | older builds only | removed by UseLocal cleanup, MoveLegacy, DropLegacy, install, wipe, detach |
| `h_local`, `h_session` | same file | `record` on a build failure | §1.2; removed on a successful build of that keyset, RecreateLocal (`h_local`), DropLegacy (`h_session`), install, wipe |
| `renewed` | same file, `"1"` | Mint(replaces) and DropLegacy, **in the same commit** as the entry change | removed by the first successful `get()` (before posting), install, wipe |
| LOCAL keyset | prefs `masroof_local_keyset`/`masroof_local_keyset_entry` + alias `masroof_local_master` (+ `master_made`) | Tink build (get-or-create), RecreateLocal (destroy), Erase (`RecoveringAead.reset`) | shared with the backfill memory (`BackfillStore`) |
| SESSION keyset | prefs `masroof_keyset`/`masroof_session_keyset` + alias `masroof_session_master` | Tink build; `TinkKeystore.aead` resets it **only when no `k`**; `TinkKeystore.destroy` only after `detachFromSessionKeyset` | never destroyed by this policy |

`masroof_capture_key` is OUT of the backup as a whole file (`BackupPrefsInventory`), so the new keys need no inventory
entry. The app sets `allowBackup=false`. Erase clears the whole file.

### 1.2 Episode record (strict parse)
`"v1|<since>|<fails>|<corrupt>|<starts>|<lastFailAt>|<lastToken>|<c>"`: decimal longs or ints, `c ∈ {0,1}`. The parse
needs exactly 8 fields, `v1`, every number parsed, and every count ≥ 0. Anything else parses as **no episode**. The next
failure then starts a fresh one, so a damaged record can only *delay* escalation and can never speed it up.

### 1.3 What is on disk at each point of a move (legacy `k` under SESSION, LOCAL healthy)
| point | `k_local` | `k` | LOCAL keyset | next `get()` |
|---|---|---|---|---|
| start | absent | K | present or absent | MoveLegacy |
| after Tink creates the LOCAL keyset (build) | absent | K | present | MoveLegacy |
| after the `k_local` commit | K | K | present | UseLocal (+ removes `k`) |
| after the fresh read-back | K | K | present | UseLocal (+ removes `k`) |
| after the `k` remove | K | absent | present | UseLocal |

At every point at least one entry opens to K. Once the `k_local` commit has returned `true`, K is durable under LOCAL.

## 2. States × events
Durable state = (`k_local`: absent / opens / bad-tag, `k`: absent / present) plus build health for LOCAL and SESSION.
`hL` means LOCAL fails to build, `hS` means SESSION fails to build. "≤lost" means the thresholds are not met yet, and
"lost" means they are.

| state | event | next | caller sees | guaranteed |
|---|---|---|---|---|
| N (no entries: fresh install / after Erase) | get | L (Mint, no note) | key | one key, sealed and read back before return |
| N + hL ≤lost | get | N, episode +1 | Unavailable (held) | nothing minted |
| N + hL lost | get | RecreateLocal, then N → L | key | the keyset held nothing of value |
| G (`k` only: first start after upgrade) | get | L (MoveLegacy) | K | §1.3 order; `k` removed only after the read-back |
| G + hL ≤lost | get | G | held | `k` and the SESSION keyset untouched |
| G + hL lost | get | RecreateLocal, then MoveLegacy | K | K preserved |
| G + hS ≤lost (LOCAL builds) | get | G, `h_session` +1 | held | `k` kept; the session cipher stays non-resetting |
| G + hS lost | get | DropLegacy, then Mint, `renewed` | new key | ≥ 3 failures over ≥ 1 h with a working LOCAL keyset |
| G, `k` bad tag under a built SESSION | get | DropLegacy, then Mint, `renewed` | new key | deterministic |
| L (normal) | get | L | K | no writes (I10) |
| LG (`k_local` opens + `k`) | get | L (cleanup removes `k`) | K | `k` is redundant (D4) |
| L + hL ≤lost | get | L | held | `k_local` never rewritten |
| L + hL lost | get | RecreateLocal, then `k_local` bad tag, then Mint, `renewed` | new key | bounded window met |
| XG (`k_local` bad tag under a fresh build + `k`) | get | as G (Move / hold / drop) | K or held | `k_local` overwritten only by K or by a mint after SESSION is lost |
| X (`k_local` bad tag, no `k`) | get | L (Mint, `renewed`) | new key | deterministic |
| any | `install(k')` (restore) | L with k', no `k`, no episodes, no `renewed` | ok / Unavailable | §5.2 |
| any | wipe (Erase) | N; LOCAL keyset reset by `backfillReset` | - | all entries, episodes and the flag gone |
| L / LG / G / XG | detach (forget) | get() must succeed first, then the session keyset is destroyed, then `k` removed | ok / Unavailable (forget retried) | session keyset destroyed only when `k_local` opens |
| any | session cipher build | `k` absent: resetting builder; `k` present: non-resetting, failure → `SessionKeysetHeldException` | session / Empty (blob kept) | R6 |
| any | kill at any write | the same state or the next row | - | I1-I9 hold after restart |

## 3. The policy: pure, no Android, no Tink (`ingest/LocalIdKeyPolicy.kt`)

### 3.1 Types
```kotlin
enum class Keyset { LOCAL, SESSION }
enum class FailKind { CORRUPT, WEAK }
sealed interface BuildObs {
    data object NotTried : BuildObs
    data object Built : BuildObs
    data class Failed(val kind: FailKind, val attempts: Int) : BuildObs   // build attempts in THIS call (1 or 2)
}
enum class OpenObs { NOT_TRIED, OPENED, BAD_TAG }                         // BAD_TAG only under a FRESH build (§5.1)
data class Obs(
    val localEntry: Boolean, val legacyEntry: Boolean,
    val localBuild: BuildObs = BuildObs.NotTried, val localOpen: OpenObs = OpenObs.NOT_TRIED,
    val sessionBuild: BuildObs = BuildObs.NotTried, val legacyOpen: OpenObs = OpenObs.NOT_TRIED,
    val recreatedThisCall: Boolean = false,
)
data class Episode(val since: Long, val fails: Int, val corrupt: Int, val starts: Int,
                   val lastFailAt: Long, val lastToken: Long, val corroborated: Boolean)
data class Health(val local: Episode?, val session: Episode?)
sealed interface Action {
    data object BuildLocal : Action; data object OpenLocal : Action
    data object BuildSession : Action; data object OpenLegacy : Action
    data object UseLocal : Action; data object MoveLegacy : Action
    data class Mint(val replaces: Boolean) : Action
    data object RecreateLocal : Action; data object DropLegacy : Action
    data class Hold(val keyset: Keyset) : Action
}
```

### 3.2 `decide(o: Obs, h: Health, now: Long): Action`: the test oracle (first matching row wins)
"Local gone" means `!o.localEntry || o.localOpen == BAD_TAG`.

| # | condition | action |
|---|---|---|
| 1 | `localBuild == NotTried` | BuildLocal |
| 2 | `localBuild is Failed && attempts == 1` | BuildLocal (one immediate fresh retry) |
| 3 | `localBuild is Failed && lost(LOCAL, h.local, now) && !recreatedThisCall` | RecreateLocal |
| 4 | `localBuild is Failed` | Hold(LOCAL) |
| 5 | `localEntry && localOpen == NOT_TRIED` | OpenLocal |
| 6 | `localEntry && localOpen == OPENED` | UseLocal |
| 7 | local gone && `legacyEntry && sessionBuild == NotTried` | BuildSession |
| 8 | local gone && `legacyEntry && sessionBuild is Failed && attempts == 1` | BuildSession |
| 9 | local gone && `legacyEntry && sessionBuild is Failed && lost(SESSION, h.session, now)` | DropLegacy |
| 10 | local gone && `legacyEntry && sessionBuild is Failed` | Hold(SESSION) |
| 11 | local gone && `legacyEntry && legacyOpen == NOT_TRIED` | OpenLegacy |
| 12 | local gone && `legacyEntry && legacyOpen == OPENED` | MoveLegacy |
| 13 | local gone && `legacyEntry && legacyOpen == BAD_TAG` | DropLegacy |
| 14 | local gone && `!legacyEntry` | Mint(replaces = `localEntry && localOpen == BAD_TAG`) |

The table is total. Rows 5-14 are reached only with `localBuild == Built`, because rows 1-4 catch every other value.
Rows 11-13 are reached only with `sessionBuild == Built`.

### 3.3 `lost(k: Keyset, e: Episode?, now: Long): Boolean`
```kotlin
if (e == null) return false
if (e.corrupt >= CORRUPT_LIMIT /*2*/) return true
val age = now - e.since
return if (k == SESSION || e.corroborated) e.fails >= 3 && age >= 1.hours
       else e.fails >= 5 && ((age >= 6.hours && e.starts >= 2) || age >= 24.hours)
```

### 3.4 `record(e: Episode?, kind: FailKind, corroborates: Boolean, now: Long, token: Long): Episode`
- `e == null` starts an episode: `since = now`, all counts 0, `lastFailAt = 0`, `lastToken = 0`, `corroborated = false`.
- `fails += 1` only when this is the first failure of the episode, or `now - lastFailAt >= 60_000`, or `now < lastFailAt`
  (the clock moved back). `lastFailAt = now` whenever `fails` was incremented.
- `corrupt += 1` for every CORRUPT observation (not rate-limited: CORRUPT depends only on the stored content).
- `starts += 1` when `token != lastToken`, then `lastToken = token`.
- `since = min(since, now)`: a clock set back restarts the age (documented; this can only delay escalation).
- `corroborated = corroborated || corroborates`, sticky for the episode.

### 3.5 `classify(e: Throwable): FailKind`
CORRUPT if the first 5 causes contain a `java.io.CharConversionException` or a class whose simple name contains
`InvalidProtocolBuffer`. Both come from parsing the stored keyset string, so they depend only on its content.
Everything else is WEAK: IOException, GeneralSecurityException (including `InvalidKeyException` "Keystore cannot load
the key", `UnrecoverableKeyException`, and a keyset unwrap bad tag), ProviderException, and any other
RuntimeException. `corroborates(e)` for LOCAL is `LocalKeystore.isKeyLost(e) || LocalKeystore.aliasGone(context)`.
`aliasGone` is the existing `needsRecovery(hasKeyset, containsAlias, master_made)`, and an exception from
`containsAlias` counts as present.

### 3.6 Why these numbers
- The Keystore failures known to clear by themselves are a keystore2 or binder restart (`ProviderException`,
  `KeyStoreException` with a system error), the per-key upgrade on first use after an OS update, and
  `UnrecoverableKeyException: Failed to obtain information about key` with a transient error code. They last seconds
  to minutes and almost always clear after a process restart. Five counted failures at least a minute apart, over 6 h
  and two process lifetimes, make a transient explanation implausible. The 24 h rule bounds the case of a process that
  never restarts.
- Corroboration means another witness agrees the key is gone. That is an exception class that names a gone key, a
  missing alias on a phone whose Keystore once made it, or, for SESSION, another Keystore key that built in this same
  call. It cuts the window to 1 h with 3 counted failures.
- CORRUPT depends only on the content, so it needs only two parses (the immediate retry of row 2 or row 8).
- The costs are asymmetric. A wrong mint means re-delivered or backfilled SMS can be recorded twice (the STORY-173 cards
  catch most of them) and the purge memory no longer matches. A wrong hold delays entries but never loses them. So the
  windows are long but bounded.
- The one-minute counting gap means a burst (an SMS storm, several workers) cannot speed up escalation. Only time and
  process starts can.

## 4. Invariants (asserted after every step of the matrix, §7.3)
- **I1 Only Unavailable.** `get()` and `install()` return 32 bytes or throw `LocalIdKeyUnavailableException`. Nothing
  else escapes, except an `Error` (the harness's `Killed`) or `IllegalArgumentException` from `install` on bad input.
- **I2 Transient never replaces (R1).** In every scenario whose fault clears before `lost()` holds, every key returned
  over the whole run equals the first key returned. `k_local` and `k` are never rewritten with other bytes, and the
  LOCAL keyset is never destroyed.
- **I3 Replacement is justified.** When a returned key differs from the previous one, the oracle allowed it: at that
  moment `decide` produced Mint after a bad tag under a fresh build, or after `lost()` held for the keyset that sealed
  the previous key, or the harness ran install or wipe.
- **I4 Bounded (R2).** With a permanent fault, `get()` returns a key by `t0 + bound(scenario) + 15 min` of fake time:
  0 for corrupt or bad tag, 1 h for corroborated or SESSION, 6 h with hourly process restarts, 24 h without restarts,
  plus 1 h when SESSION is also permanently gone.
- **I5 Session keyset safety (R6).** Every reset or destroy of the fake SESSION keyset happens while the prefs have no
  `k`.
- **I6 Durable return.** After a kill and restart, the next successful `get()` returns the last key returned before the
  kill, unless I3 allows a change.
- **I7 Hygiene.** After any successful `get()`: no `k`, no `h_local`, no `h_session`, no `renewed`. After wipe the
  prefs file is empty.
- **I8 Note.** The notification is posted at most once per replacing mint or drop, and never for N → L (first install,
  after Erase).
- **I9 Monotone episode.** Within one episode `fails`, `corrupt` and `starts` never decrease, and `since` never
  increases unless the clock moved back.
- **I10 Quiet hot path.** A `get()` in state L with no episode and no flag performs zero prefs writes and zero fresh
  builds.

## 5. Algorithms per entry point

### 5.1 `KeystoreLocalIdKey.get()` (`@Synchronized`): the driver
```
val now = clock.millis(); var o = Obs(prefs.has(K_LOCAL), prefs.has(K_LEGACY)); var h = readHealth()
var localAead: Aead? = null; var cause: Throwable? = null; var opened: ByteArray? = null
repeat(MAX_STEPS /*16*/) {
  when (val a = decide(o, h, now)) {
    BuildLocal   -> try { localAead = local.build(fresh = o.localBuild != NotTried); o = o.copy(localBuild = Built); h = clearIfPresent(LOCAL, h) }
                    catch (e: Exception) { cause = e; h = recordAndStore(LOCAL, e, h, now)
                                           o = o.copy(localBuild = Failed(classify(e), attempts(o.localBuild) + 1)) }
    OpenLocal    -> open k_local with localAead: OK -> OPENED (keep bytes);
                    GeneralSecurityException / IllegalArgumentException -> if localAead came from the cache: rebuild fresh
                    (a failure here is handled exactly like BuildLocal's catch with attempts = 2) and reopen once;
                    a rejection under a FRESH build -> BAD_TAG. Any other exception -> localBuild = Failed(WEAK, 2).
    BuildSession -> same as BuildLocal for SESSION (session.build always fresh; record with corroborates = true)
    OpenLegacy   -> open k with the session AEAD: OK -> OPENED; GSE / IAE -> BAD_TAG; other -> sessionBuild = Failed(WEAK, 2)
    UseLocal     -> cleanup(); return opened
    MoveLegacy   -> seal(opened, replaces = false); prefs.commit(removes = {k}) [failure ignored: next call cleans up]; clearEpisodes(); return opened
    Mint(r)      -> val fresh = 32 random bytes; seal(fresh, replaces = r); clearEpisodes(); return fresh
    RecreateLocal-> local.recreate(); prefs.commit(removes = {h_local}); h = readHealth()
                    o = Obs(prefs.has(K_LOCAL), prefs.has(K_LEGACY), recreatedThisCall = true)
    DropLegacy   -> prefs.commit(removes = {k, h_session}, puts = {renewed: "1"}); h = readHealth()
                    o = o.copy(legacyEntry = false, sessionBuild = NotTried, legacyOpen = NOT_TRIED)
    Hold(_)      -> throw LocalIdKeyUnavailableException(cause ?: IllegalStateException("held"))
  }
}
throw LocalIdKeyUnavailableException(IllegalStateException("policy did not settle"))   // unreachable; defensive
```
- `seal(key, replaces)`: `ct = localAead.encrypt(key, AAD)`, then
  `prefs.commit(puts = {k_local: b64(ct)} + (renewed if replaces))`. Then read back through
  `local.build(fresh = true)` and compare with `key`. The plaintext copy is zeroed in `finally`. An encrypt exception, a
  failed fresh build or a mismatch counts as a LOCAL failure (`recordAndStore(LOCAL, e)`) and throws Unavailable. A
  commit that throws `IOException` throws Unavailable **without** recording (D9).
- `recordAndStore`: computes `record(...)`, writes it with `prefs.commit` (a write failure is ignored, so escalation
  can only be delayed) and returns the new `Health`.
- `clearIfPresent` / `clearEpisodes`: a write only when the record exists (I10).
- `cleanup()`: if `o.legacyEntry`, `prefs.commit(removes = {k})`; clear the episodes if any. Write failures are
  ignored; the next call repeats the cleanup.
- After the return value is fixed (outside the step loop, still inside `get()`): if `prefs.has("renewed")`, run
  `prefs.commit(removes = {renewed})` first and **then** `notice.post()`. Both are best effort (`runCatching`); remove
  first, so a kill can only drop the note, never repeat it.
- Nothing but `Exception` is caught. `CancellationException` cannot occur: `get()` is not a suspending function.
- Error messages never contain key bytes. The cause is the Keystore or Tink exception.

### 5.2 `install(key)`: the STORY-184 `LocalIdKeyStore` port, `@Synchronized`
`require(key.size == 32 && key.any { it != 0.toByte() })`. Run the driver until `localBuild == Built`, using rows 1-4
only (BuildLocal, RecreateLocal, Hold). Then `prefs.commit(puts = {k_local}, removes = {k, renewed})`, read back fresh
and compare, and clear both episodes. A restore replaces the key by user decision, so the legacy entry and the note
no longer apply. On a throw, either the old `k_local` or the new one is fully in place: one atomic SharedPreferences
commit. The call is idempotent. `BackupModule` binds `LocalIdKeyStore { localIdKey.install(it) }`, done by whichever
of 182/184 merges second.

### 5.3 `wipe()` (Erase all data)
`prefs.clear()`: `k_local`, `k`, both episodes and `renewed` all live in `masroof_capture_key`. `RoomDataWiper`
already runs `capture.clear()` and then `backfillReset` (`RecoveringAead.reset()`, which destroys the LOCAL keyset).
A kill between the two leaves N plus a stale keyset, and the next `get()` mints with no note. Held intake jobs carry
`SMS_INTAKE_TAG` and are cancelled by the existing `cancelAllWorkByTag`.

### 5.4 `detachFromSessionKeyset(destroy)` (forget step)
No change in shape: `get().fill(0)`, then `destroy()`, then `prefs.commit(removes = {k})` (normally a no-op, since
`get()` already removed it). If `get()` throws Unavailable, the forget step keeps `forgetPending` and the next Settings
open or import retries it, within the R2 bound.

### 5.5 Session token cipher (`data/auth/SessionAead.kt`)
```kotlin
fun sessionAeadAfterMove(localIdKey: LocalIdKey, resetting: () -> Aead, safe: () -> Aead): Aead {
    try { localIdKey.get().fill(0) } catch (_: LocalIdKeyUnavailableException) { }
    if (!localIdKey.dependsOnSessionKeyset()) return resetting()
    return try { safe() } catch (e: Exception) { throw SessionKeysetHeldException(e) }   // : IOException
}
fun sessionTokenCipher(context: Context, localIdKey: LocalIdKey): LazyTokenCipher =
    LazyTokenCipher { AeadTokenCipher(sessionAeadAfterMove(localIdKey, { TinkKeystore.aead(context) }, { TinkKeystore.aeadOrThrow(context) })) }
```
`AppModule.tokenCipher` returns `sessionTokenCipher(context, localIdKey)`. This pins the wiring in one testable
function. `EncryptedSessionStore.load()` catches `SessionKeysetHeldException` before the generic catches and returns
`LoadedSession.Empty` **without** `clear()`. A sign-in while held fails at `save()`; it works once the key resolves.

### 5.6 Intake (`ingest/SmsWork.kt`)
- `intakeResult(attempt: Int, hold: (() -> Unit)? = null, accept: suspend () -> Disposition)`. Existing call sites
  `intakeResult(attempt) { ... }` still compile.
  - `LocalIdKeyUnavailableException` with `hold != null`: run `hold()`, return `success()`. If `hold()` throws, return
    `retry()`.
  - With `hold == null`: `retry()` with no attempt cap. The key cause never returns `failure()`.
  - `SQLiteException` keeps `MAX_INTAKE_ATTEMPTS`. A requeued job starts at attempt 0, so holding never uses up that
    budget.
- `SmsWorkScheduler.enqueueHeld(message, delayMillis) = enqueueIntake(message)` is a default method, so existing fakes
  still compile. `WorkManagerSmsScheduler` overrides it: the same request as `enqueueIntake` (same tag, same input
  data including `ts`) plus `setInitialDelay(KEY_HOLD_DELAY_MILLIS = 15 min)`.
- `SmsIntakeWorker` gains `private val scheduler: SmsWorkScheduler? = null`. Hilt injects it; the 3-argument test
  constructors still compile. It passes `hold = scheduler?.let { s -> { s.enqueueHeld(message, KEY_HOLD_DELAY_MILLIS) } }`.
- A kill after the requeue and before `success()` runs the old job again. The local id dedups it (same key, or the
  same new key for both).

### 5.7 Backfill, backup, purge
- `SmsBackfillWorker`: `LocalIdKeyUnavailableException` returns `retry()` always, with no `STOPPED` for this cause. The
  saved position is kept, the phase stays RUNNING, and the SMS stay in the inbox. `SQLiteException` keeps
  `MAX_BACKFILL_ATTEMPTS`.
- `BackupWorker`: unchanged behaviour (`KEY_UNAVAILABLE`). A new seam, `fun interface BackupSinkOpener { fun open(uri:
  Uri): OutputStream? }`, is bound in `BackupModule` to `contentResolver.openOutputStream(uri, "wt")`. The worker
  gets it as a constructor parameter with that default, so the key-before-open order becomes testable (the MINOR).
- `PurgeDeletedWorker`: `catch (_: LocalIdKeyUnavailableException) { Result.retry() }`. `rememberPurged` runs before
  `purgeDeletedBefore`, so nothing is purged without being remembered.

### 5.8 `LocalKeystore` / `RecoveringAead` (`data/auth/LocalKeystore.kt`)
- `LocalKeystore.aead(context, onKeyRecreated)` builds `RecoveringAead(build = { build(context).also {
  rememberMasterMade(context) } }, destroy = { destroy(context) }, onKeyRecreated = onKeyRecreated)`. It passes no
  `recoverIfLost` (the default is `{ false }`). The private `recoverIfLost(context)` and `buildRecovering` are
  deleted. `needsRecovery`, `isKeyLost`, `rememberMasterMade` and `MASTER_MADE` stay; their main-branch tests keep
  passing and they now feed `corroborates` (§3.5). New: `internal fun aliasGone(context): Boolean`.
- `RecoveringAead` gains two methods:
  - `@Synchronized fun primitive(fresh: Boolean): Aead { if (fresh) current = null; return aead() }`: never destroys.
  - `@Synchronized fun recreate() { runCatching { onKeyRecreated() }; reset() }`: the policy's escalation only.
  `encrypt`, `decrypt` (one rebuild, never a destroy), the `recoverIfLost` constructor parameter (main tests use it) and
  `reset()` are unchanged.
- `KeysetPort` (in `LocalIdKey.kt`): `build(fresh)`, `corroborates(e)`, `recreate()`. LOCAL wraps `RecoveringAead`
  plus `aliasGone`. SESSION wraps `TinkKeystore.aeadOrThrow(context)`, whose `recreate()` is never called (it throws
  `UnsupportedOperationException` to make that explicit).

## 6. What the user sees
- **During a hold:** nothing new. Entries appear when the key resolves. A backup started meanwhile fails with the
  existing `KEY_UNAVAILABLE` message. A session that cannot be opened looks signed out (the import card offers "Sign in
  again"), and the blob is kept. A "capture is waiting" banner is deferred to a follow-up; it is not in this story.
- **After a replacing mint or drop:** one notification on `CAPTURED_CHANNEL_ID` (create the channel the way
  `AndroidCapturedNotifier` does). It needs the notification permission; without it, nothing is shown. Tapping opens
  the app (immutable PendingIntent). It shows no amounts and no SMS text.

| key | values (as written in XML) | values-ar |
|---|---|---|
| `key_renewed_title` | Message matching was reset | تمت إعادة ضبط مطابقة الرسائل |
| `key_renewed_text` | Masroof could not open this phone\'s message key and made a new one. If older SMS are read again, some may show up as possible duplicates for you to review. | تعذّر على مصروف فتح مفتاح الرسائل على هذا الهاتف فأنشأ مفتاحًا جديدًا. إذا قُرئت رسائل قديمة مرة أخرى فقد يظهر بعضها كتكرار محتمل لتراجعه. |

## 7. Test plan (all tag `REQ-SEC-065`; JVM where noted, Robolectric otherwise)

### 7.1 `LocalIdKeyPolicyTest` (pure JVM): the oracle
- One test per row of §3.2 (14), each building the minimal `Obs` and asserting the exact action. Totality: an
  exhaustive loop over every `Obs` combination (2×2×6×3×6×3×2 = 2,592: `BuildObs` is NotTried, Built or `Failed(CORRUPT|WEAK, 1|2)`)
  asserts that `decide` returns, and that it never returns `Mint`/`DropLegacy`/`RecreateLocal` unless the matching row
  condition holds.
- `lost()` boundaries: corrupt 1/2; corroborated fails 2/3 at 59:59/60:00; uncorroborated fails 4/5, age
  5:59:59/6:00:00, starts 1/2, age 23:59:59/24:00:00 with starts 1; SESSION uses the 1 h rule even with
  `corroborated = false`.
- `record()`: two failures 59 s apart count once and 60 s apart count twice; CORRUPT counts every time; a new token
  increments `starts` once per token; the clock moving back keeps the counts and lowers `since`; `corroborated` is
  sticky.
- Episode codec: round trip; 7 or 9 fields, `v2`, negatives and non-numbers all parse as null.
- `classify`: `CharConversionException` wrapped 3 deep gives CORRUPT; an `InvalidProtocolBufferException`-named class
  gives CORRUPT; `InvalidKeyException`, `UnrecoverableKeyException`, `ProviderException`, `IOException("disk")` and
  `IllegalStateException` give WEAK.

### 7.2 `LocalIdKeyDriverTest` (Robolectric, fakes): one named test per state × event row of §2 (20 rows)
Use `FakeKeyset` and `FakePrefs` from §7.3 and assert the next state, the caller outcome and the guarantee column.

### 7.3 `LocalIdKeyFaultMatrixTest` (Robolectric): generated
- **Fakes.**
  - `FakeKeyset(name)` has durable `generation` (0 = none; a build with 0 creates generation n+1, which is a durable
    write) and a `fault` script. Each build returns an in-memory AES-GCM AEAD keyed by the generation, so a different
    generation gives a bad tag. `destroy` sets generation 0 and clears permanent and corrupt faults, the way Tink makes
    a working keyset next time. It logs every destroy or reset together with `prefs.has("k")`, for I5.
  - `FakePrefs` implements `KeyPrefs.commit` as one atomic map update and numbers every durable write (prefs commits
    and keyset create or destroy). `killAt = (n, BEFORE|AFTER)` throws `Killed : Error` before or after applying
    write n.
  - `FakeClock`; a recording notice.
- **Scenarios (initial state × fault target × fault kind).**
  - States: N, G, L, LG, XG (`k_local` under a gone generation, plus `k`), X, and L+`renewed`.
  - Targets: LOCAL, SESSION.
  - Kinds:
    - none;
    - burst (3 build calls fail, then OK);
    - window-uncorroborated (WEAK until 5 h 59 m);
    - window-session (WEAK until 59 m);
    - permanent WEAK (`InvalidKeyException("Keystore cannot load the key")`);
    - permanent key-gone (`UnrecoverableKeyException`, corroborated);
    - corrupt (`CharConversionException`);
    - permanent `IOException`, `ProviderException` and `IllegalStateException`;
    - containsAlias reporting gone with a working build (must change nothing);
    - LOCAL and SESSION both permanent WEAK.
- **Run.** `get()` every 15 fake minutes for 30 h. A new process (new token, dropped caches) every 60 min. A variant
  has no restarts. At hours 2 and 26 the run also calls the session cipher build, and once `detach`. Separate short
  runs cover `install(k')` and wipe at a random step.
- **Kills.** First a dry run counts the durable writes W. Then for every n in 1..W, BEFORE and AFTER: kill at write n,
  restart the process at the same fake time and continue. Double kill: after the first kill, also kill at write j ∈ {1,
  2, 3} of the next process's first call.
- **Assertions.** I1-I10 after every call, plus the expected final key per scenario. Transient kinds keep the original
  key (or the first minted one for N). Permanent LOCAL with G or LG gives K; permanent LOCAL with L or X gives a new key
  and exactly one note. Permanent SESSION in state L changes nothing and causes no hold. Permanent SESSION with G or XG
  gives a new key and one note within 1 h 15 m. Corrupt resolves in the first call. Both-permanent resolves within
  26 h.
- The matrix runs in `make check` (`testDebugUnitTest`). It must finish in < 60 s. Use in-memory AEADs; no real Tink.

### 7.4 Regression repros (one named test each)
| id | loop | test |
|---|---|---|
| L2a | 2 | `a local keyset that fails to build twice holds and returns the same key on the next call` |
| L2b | 2 | `the session cipher never resets the session keyset while k exists` (fake resetting builder records calls; `k` present + safe() failing → `SessionKeysetHeldException`, zero resets) |
| L3a | 3 | `a corrupt keyset never escapes as a raw IOException and intake requeues the message` |
| L3b | 3 | `a keyset recreated in a process that died before sealing is settled by the next process without memory` |
| L3c | 3 | `nothing remembered from an earlier recreation can overwrite k_local later` (recreate, mint K2, then 3 transient failures: K2 stays) |
| L4 | 4 | `k_local lost, legacy present and the session master key gone for good: held, then one new key within 1 h 15 m, no message dropped` (intake through `intakeResult` with a recording `hold`; 0 failures) |
| M1 | 4 | `containsAlias false with a building keyset destroys nothing` |
| M2 | 4 | `LocalIdKeyRealTinkTest`: real Tink under Robolectric, `zz-not-a-keyset` in `masroof_local_keyset` with a legacy key under a real session keyset gives the legacy bytes in one call; the same without legacy gives one minted key kept across 3 restarts; a corrupt session keyset with legacy and no `k_local` gives a minted key and the session keyset bytes unchanged until `k` is gone |
| M3 | 4 | `SessionTokenCipherTest`: `sessionTokenCipher(context, key)` with real Tink: a corrupt session keyset and `k` present keeps the keyset bytes; with `k` absent the resetting builder recovers. Source scan: `TinkKeystore.aead(` appears only in `SessionAead.kt`, `TinkKeystore.destroy(` only in `ServerForgetter.kt` |
| M4 | 4 | `BackupWorkerTest`: `key unavailable never opens the destination` (a recording `BackupSinkOpener`: zero calls, failure `KEY_UNAVAILABLE`) |
| N1 | - | `IntakeHoldTest`: Unavailable at attempts 0, 9, 10 and 50 → `hold` called and `success()`; `hold` throws → `retry()`; `hold == null` → `retry()` at attempt 50; SQLite at attempt 10 → `failure()` (unchanged) |
| N2 | - | `a backfill with the key unavailable at attempt 50 retries and stays RUNNING at its saved position` |
| N3 | - | `the purge with the key unavailable deletes nothing and retries` |
| N4 | - | `a replacing mint posts the note once; first install and after Erase post nothing; a kill after the flag removal posts nothing` |
| N5 | - | `install contract`: the four `LocalIdKeyStore` clauses (32 non-zero bytes; idempotent; reads back equal; a transient LOCAL failure leaves the old key intact), plus `install removes k and renewed` |
| N6 | - | `a session that cannot be opened while k exists is Empty and its blob is kept` |

### 7.5 Existing branch tests to rewrite (none of them is on `main`, so test integrity vs main is unaffected)
`LocalIdKeyMigrationTest` (18) and `SessionKeysetSafetyTest` (3) move to the new constructor
`KeystoreLocalIdKey(prefs, local: KeysetPort, session: KeysetPort, clock, notice, token)`, with one `KeyPrefs`; the
two old ones are the same file in production. Changed expectations, which the verifier should judge against this
note:
- `a local AEAD that cannot be built at all is unavailable, never lost` becomes `... is held within the window and
  recreated only after the bound`.
- `intake retries ... and does not drop the message` asserts requeue plus success instead of retry, up to attempt 10.
- Tests asserting that `k` stays after a move until forget now assert that `k` is removed after the read-back (D4).
- `a sealed key whose AEAD fails to build twice throws, ...`: rename it to `... holds, ...` (the loop-4 NIT).
Tests on `main` that must still pass unchanged: `BackfillRound4/5/6Test` (`RecoveringAead` constructor with
`recoverIfLost`, `needsRecovery`, `isKeyLost`), `SmsCapturedTest` (`intakeResult(attempt) { }`), `SmsPipelineTest`
(`FakeSmsScheduler`), `LocalCaptureTest` / `StartupBlockedTest` (3-argument `SmsIntakeWorker`), `PurgeSchedulerTest`,
`BackupWorkerTest`, `EraseAndSessionDataTest`, `LocalSafetyRound1Test`, `BackupPrefsGuardTest`.

Test levels: unit (pure JVM) for §7.1; Robolectric integration for §7.2-7.4. No contract, migration, e2e or eval
impact.

## 8. Code changes, file by file (production ≈ 290 changed lines; tests ≈ 700)
| file | change | ≈ lines |
|---|---|---|
| `ingest/LocalIdKeyPolicy.kt` (new) | §3 types, `decide`, `lost`, `record`, `classify`, episode codec, constants | +120 |
| `ingest/LocalIdKey.kt` | interface: `install(key)` (default throws `UnsupportedOperationException`), `dependsOnSessionKeyset()` (default false); `KeyPrefs.commit(puts, removes)`; `SharedKeyPrefs` checks the `commit()` result; `KeysetPort`; the `KeystoreLocalIdKey` driver §5.1-5.4 replaces `openLocal`/`openLegacy`/`buildSession`/`corruptOrThrow`/round-trip probe; KDoc table replaced by a pointer to this note | −150 +140 |
| `ingest/KeyRenewedNotice.kt` (new) | `fun interface KeyRenewedNotice { fun post() }` + `AndroidKeyRenewedNotice` | +35 |
| `data/auth/LocalKeystore.kt` | §5.8 | −30 +15 |
| `data/auth/SessionAead.kt` | §5.5 + `SessionKeysetHeldException` + `sessionTokenCipher` | +12 |
| `data/auth/EncryptedSessionStore.kt` | catch `SessionKeysetHeldException` → `Empty`, no clear | +3 |
| `di/AppModule.kt` | `tokenCipher` → `sessionTokenCipher`; `localIdKey` binds the new constructor (ports, `Clock`, notice); `@Binds` for the notice | ±12 |
| `ingest/SmsWork.kt` | §5.6 | +18 |
| `ingest/backfill/BackfillWork.kt` | §5.7 | ±6 |
| `data/edit/DeletedEntryPurge.kt` | §5.7 | +3 |
| `data/backup/BackupWorker.kt` + `di/BackupModule.kt` | `BackupSinkOpener` seam and binding | +12 |
| `res/values/strings.xml`, `res/values-ar/strings.xml` | 2 strings each | +4 |
| `data/serverimport/ServerForgetter.kt` | none (KDoc: "`k` is removed by get() after the move") | ±2 |

## 9. Threats (STRIDE; boundary L5 at rest: Keystore, keysets, app-private prefs; and the session cipher)
- **Spoofing.** No new input source or exported component. The notification PendingIntent is immutable and opens the
  launcher activity only.
- **Tampering.** Editing `h_local`/`h_session` needs app-private write access (root). A forged record could speed up a
  mint (the cost is duplicates, not disclosure). A damaged record parses as "none" and can only delay. Planting
  `k_local` requires the same access and is out of scope. `allowBackup=false`, and the file is OUT of the backup
  format, so a crafted backup cannot carry episodes.
- **Repudiation.** Not applicable (single-user local app). A PROGRESS-style log is not needed; the counts are in the
  record.
- **Information disclosure.** Key bytes never appear in exceptions or logs. Read-back and opened copies are zeroed in
  `finally`. Held intake jobs keep the (capped, masked) SMS text in WorkManager's app-private database for up to the
  R2 bound, as the current retry does, and Erase cancels them by tag. `semgrep no-log-body` stays green: no new logging.
- **Denial of service.** An SMS storm during a hold costs one Keystore build attempt per job every 15 min. It cannot
  speed up escalation (one counted failure per minute) and cannot fill the attempt cap (requeue). With a permanent
  failure the stop is bounded at ≤ 26 h; there is no state that only Erase can leave.
- **Elevation of privilege.** None: no new permission, no exported component, no IPC.
- **security-reviewer sign-off is required.** This touches the local key handling, ingestion (intake hold) and the
  session cipher.

## 10. Notes for the builder
- Keep the policy file free of Android and Tink imports. `LocalIdKeyPolicyTest` runs as a plain JUnit test.
- Do not catch `Throwable` or `Error` anywhere in the driver. The matrix's `Killed` must pass through.
- `KEY_HOLD_DELAY_MILLIS`, the thresholds and `MAX_STEPS` are `internal const` in the policy file, so the tests use the
  same values.
- Out of scope, listed for follow-ups: a "capture is waiting" banner during a hold, and a holdover for a disk-full
  phone (prefs writes failing for days delay escalation without bound, because the episode cannot be written; Room
  cannot write either, so capture is stopped by the disk, not by the key).
