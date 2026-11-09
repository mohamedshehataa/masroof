package eg.masroof.data.auth

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/**
 * The key for data that belongs to the phone, not to a server session (STORY-186: the sealed memory of "Read past SMS"; the
 * local-id key follows in STORY-182). Its own keyset file and its own hardware-backed Keystore master key, with the same AES-256-GCM
 * template as [TinkKeystore], so logout, session expiry and the server "forget" step (which destroy the session keyset) never touch
 * it. Only "Erase all data" resets it. If the OS loses the master key, a new one is made and what was sealed is simply unreadable
 * (the memory starts empty); nothing crashes.
 */
object LocalKeystore {
    internal const val KEYSET_PREFS = "masroof_local_keyset"
    internal const val KEYSET_NAME = "masroof_local_keyset_entry"
    private const val MASTER_MADE = "master_made"
    internal const val MASTER_KEY_ALIAS = "masroof_local_master"
    private const val MAX_CAUSES = 5
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    /** An AEAD that recovers by itself from a lost key and is reset only by [RecoveringAead.reset] (Erase all data). */
    fun aead(
        context: Context,
        onKeyRecreated: () -> Unit = {},
    ): RecoveringAead =
        RecoveringAead(
            build = { buildRecovering(context, onKeyRecreated) },
            destroy = { destroy(context) },
            recoverIfLost = { recoverIfLost(context) },
            onKeyRecreated = onKeyRecreated,
        )

    /** The "Erase all data" step: destroys the keyset entry and the master key unconditionally; a new pair is made at the next use. */
    fun reset(context: Context) = destroy(context)

    /**
     * True only when the key is provably gone: a keyset entry exists, the Keystore once made its master key ([masterWasMade]) and now has
     * none. If the Keystore never worked on this phone (Tink then keeps the keyset without a master key), nothing is lost: the keyset
     * stays rather than being wiped at every start.
     */
    internal fun needsRecovery(
        hasKeyset: Boolean,
        masterPresent: Boolean,
        masterWasMade: Boolean,
    ): Boolean = hasKeyset && !masterPresent && masterWasMade

    /** True for the failures that mean the master key can never work again (invalidated or unrecoverable), not for transient ones. */
    internal fun isKeyLost(e: Throwable): Boolean =
        generateSequence(
            e,
        ) { it.cause }.take(MAX_CAUSES).any { it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException }

    private fun recoverIfLost(context: Context): Boolean {
        val prefs = context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE)
        val hasKeyset = prefs.contains(KEYSET_NAME)
        // when the Keystore cannot be asked, the key is not known to be gone: nothing is destroyed
        val present =
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(MASTER_KEY_ALIAS)
            } catch (_: GeneralSecurityException) {
                true
            } catch (_: IOException) {
                true
            } catch (_: ProviderException) {
                true
            }
        if (needsRecovery(hasKeyset, present, prefs.getBoolean(MASTER_MADE, false))) {
            destroy(context)
            return true
        }
        return false
    }

    private fun buildRecovering(
        context: Context,
        onKeyRecreated: () -> Unit,
    ): Aead =
        try {
            build(context).also { rememberMasterMade(context) }
        } catch (e: GeneralSecurityException) {
            if (!isKeyLost(e)) throw e
            destroy(context)
            onKeyRecreated()
            build(context).also { rememberMasterMade(context) }
        }

    /** Remembers that the Keystore made the master key (so "keyset without master key" later means lost, not "never worked"). */
    private fun rememberMasterMade(context: Context) {
        val present =
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(MASTER_KEY_ALIAS)
            }.getOrDefault(false)
        if (present) context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit(commit = true) { putBoolean(MASTER_MADE, true) }
    }

    private fun build(context: Context): Aead {
        AeadConfig.register()
        val handle =
            AndroidKeysetManager
                .Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** Deletes the keyset entry and the master key so the next [build] generates both afresh. */
    private fun destroy(context: Context) {
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            remove(KEYSET_NAME)
            remove(MASTER_MADE)
        }
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
        } catch (_: GeneralSecurityException) {
            // already gone or unusable: build() creates a new one
        } catch (_: IOException) {
            // same
        } catch (_: ProviderException) {
            // same
        }
    }
}

/**
 * An [Aead] that builds its key lazily. A failing seal or open never destroys the key: the cached primitive is dropped, rebuilt once
 * and tried once more, and if that fails too the failure is thrown (callers treat it as "not remembered"). The key is destroyed
 * and recreated only when it is provably gone ([recoverIfLost], run before every build) and, unconditionally, by [reset] (the
 * "Erase all data" step): a transient Keystore error must never change a key that already seals something else.
 */
class RecoveringAead(
    private val build: () -> Aead,
    private val destroy: () -> Unit,
    /** Destroys the key when it is provably gone and says so (true); run before every build. */
    private val recoverIfLost: () -> Boolean = { false },
    /** Called after a lost key was destroyed: whatever was sealed under it can never be opened (the caller clears it at once). */
    private val onKeyRecreated: () -> Unit = {},
) : Aead {
    private var current: Aead? = null

    @Synchronized
    private fun aead(): Aead =
        current ?: run {
            if (recoverIfLost()) onKeyRecreated()
            build().also { current = it }
        }

    @Synchronized
    fun reset() {
        current = null
        runCatching { destroy() }
    }

    @Synchronized
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = retryOnce { it.encrypt(plaintext, associatedData) }

    @Synchronized
    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = retryOnce { it.decrypt(ciphertext, associatedData) }

    @Suppress("TooGenericExceptionCaught") // a Keystore provider can fail with any RuntimeException: one rebuild, then give up
    private fun retryOnce(use: (Aead) -> ByteArray): ByteArray =
        try {
            use(aead())
        } catch (_: Exception) {
            current = null
            use(aead())
        }
}
