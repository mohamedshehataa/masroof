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
 */
class KeystoreLocalIdKey(
    private val current: KeyPrefs,
    private val legacy: KeyPrefs,
    private val localAead: () -> Aead,
    private val sessionAead: () -> Aead,
    /** True only when the local keyset was PROVABLY lost and recreated: an entry sealed under it can never open. */
    private val localLost: () -> Boolean = { false },
) : LocalIdKey {
    constructor(context: Context, localAead: RecoveringAead) : this(
        SharedKeyPrefs(context, "masroof_capture_key"),
        SharedKeyPrefs(context, "masroof_capture_key"),
        { localAead },
        // NON-resetting: a transient Keystore failure while reading the legacy key must never delete the keyset it was sealed under
        { TinkKeystore.aeadOrThrow(context) },
        { localAead.keyRecreated },
    )

    /**
     * Returns the key. A key is made ONLY when no entry exists (or every entry that exists is PROVABLY gone: the local keyset was lost
     * and recreated, and the legacy entry does not open under a built session keyset). An entry that exists but does not open, a
     * keyset that fails to build, a failed write or a failed read-back all THROW [LocalIdKeyUnavailableException]: callers retry
     * later, they never get a different key. Intake and the backfill worker retry; a minted key would stop every SMS from deduping.
     */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // the Keystore provider can fail with any RuntimeException: all of them mean "try later"
    override fun get(): ByteArray =
        try {
            getChecked()
        } catch (e: LocalIdKeyUnavailableException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw LocalIdKeyUnavailableException(e)
        } catch (e: RuntimeException) {
            throw LocalIdKeyUnavailableException(e)
        }

    private fun getChecked(): ByteArray {
        val sealed = current.get(CURRENT)
        if (sealed != null) {
            try {
                return unseal(sealed, localAead())
            } catch (e: GeneralSecurityException) {
                // present but not opened: a busy Keystore looks the same as a lost key, so only a PROVABLY lost key may go on
                if (!localLost()) throw LocalIdKeyUnavailableException(e)
            }
        }
        val old =
            legacy.get(LEGACY)?.let { stored ->
                // building the session AEAD may throw (not swallowed: not "no key"); only a failed OPEN under a built keyset is "gone"
                val a = sessionAead()
                try {
                    unseal(stored, a)
                } catch (_: GeneralSecurityException) {
                    null
                }
            }
        val key = old ?: ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        seal(key)
        return key
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
