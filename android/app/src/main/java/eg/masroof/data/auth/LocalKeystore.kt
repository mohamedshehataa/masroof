package eg.masroof.data.auth

import android.content.Context
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
    internal const val MASTER_KEY_ALIAS = "masroof_local_master"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    /** An AEAD that recovers by itself from a lost key and is reset only by [RecoveringAead.reset] (Erase all data). */
    fun aead(context: Context): RecoveringAead = RecoveringAead(build = { build(context) }, destroy = { destroy(context) })

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
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit(commit = true) { remove(KEYSET_NAME) }
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
 * An [Aead] that builds its key lazily and, when sealing fails (the key is gone: Keystore reset by the OS), destroys the key, makes a
 * new one and seals again. Opening with a key that no longer exists fails (the caller treats the value as absent). [reset] is the
 * "Erase all data" step: the key is destroyed and a new one is made at the next use.
 */
class RecoveringAead(
    private val build: () -> Aead,
    private val destroy: () -> Unit,
) : Aead {
    private var current: Aead? = null

    @Synchronized
    private fun aead(): Aead = current ?: build().also { current = it }

    @Synchronized
    fun reset() {
        current = null
        runCatching { destroy() }
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // a Keystore provider can fail with any RuntimeException: all mean "the key is unusable"
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray =
        try {
            aead().encrypt(plaintext, associatedData)
        } catch (_: Exception) {
            current = null
            runCatching { destroy() }
            aead().encrypt(plaintext, associatedData)
        }

    @Synchronized
    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = aead().decrypt(ciphertext, associatedData)
}
