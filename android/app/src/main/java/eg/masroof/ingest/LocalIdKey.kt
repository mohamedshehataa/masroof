package eg.masroof.ingest

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import eg.masroof.data.auth.LocalKeystore
import eg.masroof.data.auth.RecoveringAead
import eg.masroof.data.auth.TinkKeystore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Per-installation secret that keys the local message id. Never uploaded; wiped by "Erase all data" only. */
interface LocalIdKey {
    /** 32 bytes, or [LocalIdKeyUnavailableException]: nothing else ever leaves. The caller zeroes the copy. */
    fun get(): ByteArray

    fun wipe()

    /**
     * Runs [destroy], which deletes the SESSION keyset (REQ-SEC-065), after making sure this key no longer depends on it. A key
     * that never lived in that keyset just runs it.
     */
    fun detachFromSessionKeyset(destroy: () -> Unit) = destroy()

    /** A restore replaces the key by the user's decision (STORY-184's `LocalIdKeyStore` port). */
    fun install(key: ByteArray): Unit = throw UnsupportedOperationException("install")

    /** True while an unmoved legacy key is sealed under the SESSION keyset: that keyset must not be reset. */
    fun dependsOnSessionKeyset(): Boolean = false
}

/** The small string store of the key (SharedPreferences in production). Writes are CHECKED: a failed commit throws. */
interface KeyPrefs {
    fun get(name: String): String?

    fun has(name: String): Boolean = get(name) != null

    /** One atomic update. Throws [IOException] when it did not reach the disk. */
    fun commit(
        puts: Map<String, String> = emptyMap(),
        removes: Set<String> = emptySet(),
    )

    fun clear()
}

/** A keyset the policy builds an AEAD from. [build] returns the raw primitive and never destroys anything. */
interface KeysetPort {
    fun build(fresh: Boolean): Aead

    /** Another witness that the keyset is gone for good (an exception that names it, a missing alias). */
    fun corroborates(e: Throwable): Boolean

    /** The policy's escalation only: destroy the keyset so the next build makes a new one. */
    fun recreate()
}

/** One notification after a key was replaced (design D10). */
fun interface KeyRenewedNotice {
    fun post()
}

/**
 * The 32-byte local-id key, sealed under `LocalKeystore`. What happens on a Keystore or Tink failure is the pure policy in
 * `LocalIdKeyPolicy.kt` (docs/design/STORY-182-local-id-key-policy.md): a bad tag under a keyset that BUILT is certain loss; a build
 * failure is held, counted at most once a minute, and escalates only on bounded evidence. Nothing but
 * [LocalIdKeyUnavailableException] (or an `Error`) ever leaves [get] or [install]. Key bytes never appear in exceptions.
 */
class KeystoreLocalIdKey(
    private val prefs: KeyPrefs,
    private val local: KeysetPort,
    private val session: KeysetPort,
    private val clock: () -> Long,
    private val notice: KeyRenewedNotice,
    /** Changes per process start (de-duplicates the start count of an episode). */
    private val token: Long,
) : LocalIdKey {
    constructor(context: Context, localAead: RecoveringAead, notice: KeyRenewedNotice) : this(
        SharedKeyPrefs(context, "masroof_capture_key"),
        LocalPort(context, localAead),
        SessionPort(context),
        System::currentTimeMillis,
        notice,
        System.nanoTime(),
    )

    @Synchronized
    override fun get(): ByteArray {
        val key = Run(clock()).get()
        // the flag is removed BEFORE the note is posted: a kill can drop the note, never repeat it
        if (prefs.has(RENEWED)) {
            if (quietly { prefs.commit(removes = setOf(RENEWED)) }) quietly { notice.post() }
        }
        return key
    }

    @Synchronized
    override fun install(key: ByteArray) {
        require(key.size == KEY_BYTES && key.any { it != 0.toByte() }) { "a key is 32 non-zero bytes" }
        val run = Run(clock())
        val aead = run.ensureLocalBuilt()
        run.seal(aead, key, replaces = false, extraRemoves = setOf(LEGACY, RENEWED))
        run.clearEpisodes()
    }

    @Synchronized
    override fun dependsOnSessionKeyset(): Boolean = prefs.has(LEGACY)

    @Synchronized
    override fun detachFromSessionKeyset(destroy: () -> Unit) {
        // the key is read (and so moved) first; the copy is zeroed
        get().fill(0)
        destroy()
        quietly { prefs.commit(removes = setOf(LEGACY)) }
    }

    @Synchronized
    override fun wipe() = prefs.clear()

    /** One call of the driver (design §5.1). All state is re-read from [prefs]; nothing is remembered between calls. */
    @Suppress("TooManyFunctions") // one small step per action of the policy table
    private inner class Run(
        private val now: Long,
    ) {
        private var o = Obs(localEntry = prefs.has(K_LOCAL), legacyEntry = prefs.has(LEGACY))
        private var h = readHealth()
        private var localAead: Aead? = null
        private var localFresh = false
        private var sessionAead: Aead? = null
        private var cause: Throwable? = null
        private var opened: ByteArray? = null

        fun get(): ByteArray {
            repeat(MAX_STEPS) {
                when (val a = decide(o, h, now)) {
                    Action.BuildLocal -> buildLocal()
                    Action.OpenLocal -> openLocal()
                    Action.BuildSession -> buildSession()
                    Action.OpenLegacy -> openLegacy()
                    Action.UseLocal -> {
                        cleanup()
                        return opened!!
                    }
                    Action.MoveLegacy -> {
                        val key = opened!!
                        seal(localAead!!, key, replaces = false)
                        quietly { prefs.commit(removes = setOf(LEGACY)) } // failure ignored: the next call cleans up
                        clearEpisodes()
                        return key
                    }
                    is Action.Mint -> {
                        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
                        seal(localAead!!, fresh, replaces = a.replaces)
                        clearEpisodes()
                        return fresh
                    }
                    Action.RecreateLocal -> recreateLocal()
                    Action.DropLegacy -> dropLegacy()
                    is Action.Hold -> throw LocalIdKeyUnavailableException(cause ?: IllegalStateException("held"))
                }
            }
            throw LocalIdKeyUnavailableException(IllegalStateException("policy did not settle"))
        }

        /** Rows 1-4 only: a built LOCAL keyset, or Unavailable (used by [install]). */
        fun ensureLocalBuilt(): Aead {
            var obs = Obs(localEntry = false, legacyEntry = false)
            repeat(MAX_STEPS) {
                when (decide(obs, h, now)) {
                    Action.BuildLocal -> {
                        o = obs
                        buildLocal()
                        obs = o
                        if (obs.localBuild == BuildObs.Built) return localAead!!
                    }
                    Action.RecreateLocal -> {
                        recreateLocal()
                        obs = Obs(localEntry = false, legacyEntry = false, recreatedThisCall = true)
                    }
                    else -> throw LocalIdKeyUnavailableException(cause ?: IllegalStateException("held"))
                }
            }
            throw LocalIdKeyUnavailableException(IllegalStateException("policy did not settle"))
        }

        private fun readHealth() = Health(decodeEpisode(prefs.get(H_LOCAL)), decodeEpisode(prefs.get(H_SESSION)))

        private fun attempts(b: BuildObs) = (b as? BuildObs.Failed)?.attempts ?: 0

        @Suppress("TooGenericExceptionCaught") // the Keystore provider can fail with any RuntimeException: all are a failed build
        private fun buildLocal() {
            val fresh = o.localBuild != BuildObs.NotTried
            try {
                localAead = local.build(fresh)
                localFresh = fresh
                o = o.copy(localBuild = BuildObs.Built)
                clearEpisode(Keyset.LOCAL)
            } catch (e: Exception) {
                failLocal(e, attempts(o.localBuild) + 1)
            }
        }

        private fun failLocal(
            e: Exception,
            attempts: Int,
        ) {
            cause = e
            note(Keyset.LOCAL, e)
            o = o.copy(localBuild = BuildObs.Failed(classify(e), attempts))
        }

        @Suppress("TooGenericExceptionCaught")
        private fun openLocal() {
            val stored = prefs.get(K_LOCAL) ?: return
            try {
                opened = unseal(stored, localAead!!)
                o = o.copy(localOpen = OpenObs.OPENED)
            } catch (_: GeneralSecurityException) {
                badTagLocal(stored)
            } catch (_: IllegalArgumentException) {
                badTagLocal(stored)
            } catch (e: Exception) {
                failLocal(e, 2)
            }
        }

        /** A rejection through a cached primitive is confirmed with a fresh build before it counts (a bad tag is then certain). */
        @Suppress("TooGenericExceptionCaught")
        private fun badTagLocal(stored: String) {
            if (localFresh) {
                o = o.copy(localOpen = OpenObs.BAD_TAG)
                return
            }
            try {
                localAead = local.build(true)
                localFresh = true
                try {
                    opened = unseal(stored, localAead!!)
                    o = o.copy(localOpen = OpenObs.OPENED)
                } catch (_: GeneralSecurityException) {
                    o = o.copy(localOpen = OpenObs.BAD_TAG)
                } catch (_: IllegalArgumentException) {
                    o = o.copy(localOpen = OpenObs.BAD_TAG)
                }
            } catch (e: Exception) {
                failLocal(e, 2)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun buildSession() {
            try {
                sessionAead = session.build(true)
                o = o.copy(sessionBuild = BuildObs.Built)
                clearEpisode(Keyset.SESSION)
            } catch (e: Exception) {
                failSession(e, attempts(o.sessionBuild) + 1)
            }
        }

        private fun failSession(
            e: Exception,
            attempts: Int,
        ) {
            cause = e
            note(Keyset.SESSION, e)
            o = o.copy(sessionBuild = BuildObs.Failed(classify(e), attempts))
        }

        @Suppress("TooGenericExceptionCaught")
        private fun openLegacy() {
            val stored = prefs.get(LEGACY) ?: return
            try {
                opened = unseal(stored, sessionAead!!)
                o = o.copy(legacyOpen = OpenObs.OPENED)
            } catch (_: GeneralSecurityException) {
                o = o.copy(legacyOpen = OpenObs.BAD_TAG)
            } catch (_: IllegalArgumentException) {
                o = o.copy(legacyOpen = OpenObs.BAD_TAG)
            } catch (e: Exception) {
                failSession(e, 2)
            }
        }

        private fun recreateLocal() {
            local.recreate()
            quietly { prefs.commit(removes = setOf(H_LOCAL)) }
            h = readHealth()
            localAead = null
            localFresh = false
            o = Obs(localEntry = prefs.has(K_LOCAL), legacyEntry = prefs.has(LEGACY), recreatedThisCall = true)
        }

        private fun dropLegacy() {
            commitOrHold(puts = mapOf(RENEWED to "1"), removes = setOf(LEGACY, H_SESSION))
            h = readHealth()
            o = o.copy(legacyEntry = false, sessionBuild = BuildObs.NotTried, legacyOpen = OpenObs.NOT_TRIED)
        }

        /** A storage failure is a hold, and is NOT counted as a Keystore failure (D9). */
        private fun commitOrHold(
            puts: Map<String, String> = emptyMap(),
            removes: Set<String> = emptySet(),
        ) {
            try {
                prefs.commit(puts, removes)
            } catch (e: IOException) {
                throw LocalIdKeyUnavailableException(e)
            }
        }

        /** Seals [key], commits it, and proves it reads back through a FRESH build (else nothing is returned). */
        @Suppress("TooGenericExceptionCaught")
        fun seal(
            aead: Aead,
            key: ByteArray,
            replaces: Boolean,
            extraRemoves: Set<String> = emptySet(),
        ) {
            val sealed =
                try {
                    Base64.encodeToString(aead.encrypt(key, AAD), Base64.NO_WRAP)
                } catch (e: Exception) {
                    note(Keyset.LOCAL, e)
                    throw LocalIdKeyUnavailableException(e)
                }
            commitOrHold(puts = mapOf(K_LOCAL to sealed) + (if (replaces) mapOf(RENEWED to "1") else emptyMap()), removes = extraRemoves)
            var back: ByteArray? = null
            try {
                back = unseal(prefs.get(K_LOCAL).orEmpty(), local.build(true))
                if (!back.contentEquals(key)) throw GeneralSecurityException("local-id key did not read back")
            } catch (e: Exception) {
                note(Keyset.LOCAL, e)
                throw LocalIdKeyUnavailableException(e)
            } finally {
                back?.fill(0)
            }
        }

        private fun cleanup() {
            if (o.legacyEntry) quietly { prefs.commit(removes = setOf(LEGACY)) }
            clearEpisodes()
        }

        fun clearEpisodes() {
            clearEpisode(Keyset.LOCAL)
            clearEpisode(Keyset.SESSION)
        }

        private fun clearEpisode(k: Keyset) {
            val has = if (k == Keyset.LOCAL) h.local != null else h.session != null
            if (!has) return
            quietly { prefs.commit(removes = setOf(if (k == Keyset.LOCAL) H_LOCAL else H_SESSION)) }
            h = if (k == Keyset.LOCAL) h.copy(local = null) else h.copy(session = null)
        }

        private fun note(
            k: Keyset,
            e: Throwable,
        ) {
            val port = if (k == Keyset.LOCAL) local else session
            val old = if (k == Keyset.LOCAL) h.local else h.session
            val next = record(old, classify(e), corroborates = k == Keyset.SESSION || port.corroborates(e), now = now, token = token)
            val name = if (k == Keyset.LOCAL) H_LOCAL else H_SESSION
            quietly { prefs.commit(puts = mapOf(name to encodeEpisode(next))) }
            h = if (k == Keyset.LOCAL) h.copy(local = next) else h.copy(session = next)
        }
    }

    private fun unseal(
        stored: String,
        aead: Aead,
    ): ByteArray = aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), AAD)

    private companion object {
        const val K_LOCAL = "k_local"
        const val LEGACY = "k"
        const val H_LOCAL = "h_local"
        const val H_SESSION = "h_session"
        const val RENEWED = "renewed"
        const val KEY_BYTES = 32
        val AAD = "masroof-local-id".toByteArray()
    }
}

/** The LOCAL keyset: the `RecoveringAead` primitive plus the alias witness. */
private class LocalPort(
    private val context: Context,
    private val aead: RecoveringAead,
) : KeysetPort {
    override fun build(fresh: Boolean): Aead = aead.primitive(fresh)

    override fun corroborates(e: Throwable): Boolean = LocalKeystore.isKeyLost(e) || LocalKeystore.aliasGone(context)

    override fun recreate() = aead.recreate()
}

/** The SESSION keyset, read with the NON-resetting builder; the policy never destroys it. */
private class SessionPort(
    private val context: Context,
) : KeysetPort {
    override fun build(fresh: Boolean): Aead = TinkKeystore.aeadOrThrow(context)

    override fun corroborates(e: Throwable): Boolean = true

    override fun recreate(): Unit = throw UnsupportedOperationException("the session keyset is never recreated by the policy")
}

/** Best effort: a failure of an [Exception] kind is ignored (true when it ran). An `Error` (a kill) is never swallowed. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal inline fun quietly(block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (_: Exception) {
        false
    }

/** The key cannot be read or written right now. It is NOT "no key": the caller holds the work and tries again, never another key. */
class LocalIdKeyUnavailableException(
    cause: Throwable,
) : IOException("local-id key unavailable", cause)

/** [KeyPrefs] over one SharedPreferences file. A commit that returns false is an error, not a silent success. */
class SharedKeyPrefs(
    private val context: Context,
    private val file: String,
) : KeyPrefs {
    private val prefs get() = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    override fun get(name: String): String? = prefs.getString(name, null)

    @android.annotation.SuppressLint("ApplySharedPref") // commit() on purpose: the result is checked and a failure is a hold
    override fun commit(
        puts: Map<String, String>,
        removes: Set<String>,
    ) {
        val e = prefs.edit()
        removes.forEach { e.remove(it) }
        puts.forEach { (k, v) -> e.putString(k, v) }
        if (!e.commit()) throw IOException("prefs commit failed")
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    override fun clear() {
        prefs.edit().clear().commit()
    }
}
