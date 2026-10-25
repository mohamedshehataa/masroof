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

/**
 * AES-256-GCM keyset, itself wrapped by a non-exportable Android Keystore master key. EncryptedSharedPreferences is
 * deprecated; Tink is what it was built on.
 *
 * Recovery: if the keyset cannot be opened (corrupt prefs, master key lost after a device-credential reset or restore)
 * it is deleted together with the master key and regenerated. Anything sealed with the old key is then unreadable,
 * which [EncryptedSessionStore] reports as Invalidated, so the user simply signs in again.
 */
object TinkKeystore {
    private const val KEYSET_PREFS = "masroof_keyset"
    private const val KEYSET_NAME = "masroof_session_keyset"
    private const val MASTER_KEY_ALIAS = "masroof_session_master"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    fun aead(context: Context): Aead {
        AeadConfig.register()
        return try {
            build(context)
        } catch (_: GeneralSecurityException) {
            reset(context)
            build(context)
        } catch (_: IOException) {
            reset(context)
            build(context)
        }
    }

    private fun build(context: Context): Aead {
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

    /** Deletes the undecryptable keyset entry and the master key so [build] generates both afresh. */
    private fun reset(context: Context) {
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit(commit = true) { remove(KEYSET_NAME) }
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
        } catch (_: GeneralSecurityException) {
            // Already gone or unusable: build() creates a new one.
        } catch (_: IOException) {
            // Same.
        }
    }
}
