package eg.masroof.ingest

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import eg.masroof.data.auth.RecoveringAead
import eg.masroof.data.auth.TinkKeystore
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Per-installation secret that keys the local message id. Never uploaded; wiped by "Erase all data" only. */
interface LocalIdKey {
    fun get(): ByteArray

    fun wipe()

    /**
     * Runs [destroy], which deletes the SESSION keyset (REQ-SEC-065), after making sure this key no longer depends on it. A key
     * that never lived in that keyset just runs it.
     */
    fun detachFromSessionKeyset(destroy: () -> Unit) = destroy()
}

/** The two small string stores of the key (SharedPreferences in production; commits are synchronous). */
interface KeyPrefs {
    fun get(name: String): String?

    fun put(
        name: String,
        value: String,
    )

    fun remove(name: String)

    fun clear()
}

/**
 * The 32-byte local-id key, sealed under `LocalKeystore` (its own keyset, never destroyed by logout, session expiry or the server
 * "forget" step). A key made by an older build is sealed under the SESSION keyset (`legacy`); it is moved with a crash-safe order:
 * seal under the local keyset into a new entry, commit, read it back and compare, and only then may the session keyset be deleted and
 * the legacy entry removed. At every point one of the two entries opens to the same bytes, so a kill or an exception at any step
 * loses nothing. Plaintext copies used for checking are zeroed.
 *
 * A key is made ONLY when no entry exists, or when every entry that exists is deterministically LOST. Loss is decided from what can
 * be observed now, never from in-memory history:
 *
 * | what happens                                                    | class     | action                                                  |
 * |-----------------------------------------------------------------|-----------|---------------------------------------------------------|
 * | local entry opens                                               | ok        | return it                                               |
 * | local entry fails to open, a test value round-trips through the | lost      | the tag is permanently bad: use the legacy key, else    |
 * | SAME built AEAD (after the build Tink runs in memory)           |           | make one key and keep it                                |
 * | local entry fails to open and the round trip fails too          | transient | throw (nothing is replaced, callers retry)              |
 * | local keyset file does not parse twice in a row (corrupt)       | lost      | reset the local keyset (existing LocalKeystore path)    |
 * | any other I/O error, provider error, busy Keystore, failed write| transient | throw [LocalIdKeyUnavailableException]                  |
 * | session keyset does not parse twice in a row (corrupt)          | lost      | the legacy key is lost                                  |
 * | legacy entry fails to open under a BUILT session keyset         | lost      | the legacy key is lost                                  |
 * | session keyset fails to build for any other reason              | transient | throw                                                   |
 *
 * Nothing but a [LocalIdKeyUnavailableException] (or a cancellation) ever leaves [get].
 */
class KeystoreLocalIdKey(
    private val current: KeyPrefs,
    private val legacy: KeyPrefs,
    private val localAead: () -> Aead,
    private val sessionAead: () -> Aead,
    /** Destroys the local keyset so the next build makes a new one (used only for a keyset file that is corrupt). */
    private val localReset: () -> Unit = {},
) : LocalIdKey {
    constructor(context: Context, localAead: RecoveringAead) : this(
        SharedKeyPrefs(context, "masroof_capture_key"),
        SharedKeyPrefs(context, "masroof_capture_key"),
        { localAead },
        // NON-resetting: a transient Keystore failure while reading the legacy key must never delete the keyset it was sealed under
        { TinkKeystore.aeadOrThrow(context) },
        { localAead.reset() },
    )

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // the Keystore provider can fail with any RuntimeException: all of them mean "try later"
    override fun get(): ByteArray =
        try {
            getChecked()
        } catch (e: LocalIdKeyUnavailableException) {
            throw e
        } catch (e: java.io.IOException) {
            throw LocalIdKeyUnavailableException(e)
        } catch (e: GeneralSecurityException) {
            throw LocalIdKeyUnavailableException(e)
        } catch (e: RuntimeException) {
            throw LocalIdKeyUnavailableException(e)
        }

    private fun getChecked(): ByteArray {
        val sealed = current.get(CURRENT)
        if (sealed != null) openLocal(sealed)?.let { return it }
        val stored = legacy.get(LEGACY)
        val old = if (stored == null) null else openLegacy(stored)
        val key = old ?: ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        seal(key)
        return key
    }

    /** The key, or null when the entry is LOST (see the table); throws when that cannot be told. */
    private fun openLocal(sealed: String): ByteArray? {
        val a = localAead()
        try {
            return unseal(sealed, a)
        } catch (e: java.io.IOException) {
            if (!isCorrupt(e)) throw LocalIdKeyUnavailableException(e)
            // RecoveringAead already rebuilt once: a keyset file that does not parse twice is permanently corrupt
            localReset()
            return null
        } catch (_: GeneralSecurityException) {
            // a bad tag, or a build that failed: the round trip below tells them apart
        }
        val probe = ByteArray(PROBE_BYTES).also { SecureRandom().nextBytes(it) }
        val roundTrip =
            try {
                a.decrypt(a.encrypt(probe, PROBE_AAD), PROBE_AAD)
            } catch (e: java.io.IOException) {
                if (isCorrupt(e)) {
                    localReset()
                    return null
                }
                throw LocalIdKeyUnavailableException(e)
            } catch (e: GeneralSecurityException) {
                throw LocalIdKeyUnavailableException(e)
            }
        if (!roundTrip.contentEquals(probe)) throw LocalIdKeyUnavailableException(GeneralSecurityException("round trip differs"))
        // The keyset is built and works now. The first failure may have been the build itself (a transient one), so the entry is
        // tried once more: only if the BUILT keyset rejects it too is the tag permanently bad (lost).
        return try {
            unseal(sealed, a)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** The legacy key, or null when it is LOST (see the table); throws when that cannot be told. */
    private fun openLegacy(stored: String): ByteArray? {
        val a =
            try {
                buildSession()
            } catch (_: CorruptSessionKeyset) {
                return null
            }
        return try {
            unseal(stored, a)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** Builds the session AEAD; null-free: a keyset that does not parse twice in a row throws [CorruptSessionKeyset]. */
    private fun buildSession(): Aead =
        try {
            sessionAead()
        } catch (first: java.io.IOException) {
            if (!isCorrupt(first)) throw LocalIdKeyUnavailableException(first)
            try {
                sessionAead()
            } catch (second: java.io.IOException) {
                if (!isCorrupt(second)) throw LocalIdKeyUnavailableException(second)
                throw CorruptSessionKeyset()
            }
        }

    private class CorruptSessionKeyset : Exception()

    private fun isCorrupt(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(MAX_CAUSES).any {
            it is java.io.CharConversionException || it.javaClass.simpleName.contains("InvalidProtocolBuffer")
        }

    private fun unseal(
        stored: String,
        aead: Aead,
    ): ByteArray =
        try {
            aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), AAD)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("bad entry", e)
        }

    /** Seals [key] under the local keyset, commits it, and proves it reads back identical (else throws, nothing is destroyed). */
    private fun seal(key: ByteArray) {
        current.put(CURRENT, Base64.encodeToString(localAead().encrypt(key, AAD), Base64.NO_WRAP))
        val check = unseal(current.get(CURRENT).orEmpty(), localAead())
        try {
            if (!check.contentEquals(key)) throw GeneralSecurityException("local-id key did not read back")
        } finally {
            check.fill(0)
        }
    }

    @Synchronized
    override fun detachFromSessionKeyset(destroy: () -> Unit) {
        // 1-3: the key is sealed under the local keyset, committed and verified (get() migrates and proves it); the copy is zeroed
        get().fill(0)
        // 4: only now the session keyset goes; if this throws, the verified entry still opens
        destroy()
        // 5: the legacy entry is useless once its keyset is gone
        legacy.remove(LEGACY)
    }

    @Synchronized
    override fun wipe() {
        current.clear()
        legacy.clear()
    }

    private companion object {
        const val CURRENT = "k_local"
        const val LEGACY = "k"
        const val KEY_BYTES = 32
        const val PROBE_BYTES = 16
        const val MAX_CAUSES = 5
        val PROBE_AAD = "masroof-local-id-probe".toByteArray()
        val AAD = "masroof-local-id".toByteArray()
    }
}

/** The key cannot be read or written right now. It is NOT "no key": the caller tries again later and never uses another key. */
class LocalIdKeyUnavailableException(
    cause: Throwable,
) : java.io.IOException("local-id key unavailable", cause)

/** [KeyPrefs] over one SharedPreferences file. */
class SharedKeyPrefs(
    private val context: Context,
    private val file: String,
) : KeyPrefs {
    private val prefs get() = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    override fun get(name: String): String? = prefs.getString(name, null)

    override fun put(
        name: String,
        value: String,
    ) = prefs.edit(commit = true) { putString(name, value) }

    override fun remove(name: String) = prefs.edit(commit = true) { remove(name) }

    override fun clear() = prefs.edit(commit = true) { clear() }
}
