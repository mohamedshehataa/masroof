package eg.masroof.data.auth

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * AES-256-GCM keyset, itself wrapped by a non-exportable Android Keystore master key. EncryptedSharedPreferences is
 * deprecated; Tink is what it was built on.
 */
object TinkKeystore {
    private const val KEYSET_PREFS = "masroof_keyset"
    private const val KEYSET_NAME = "masroof_session_keyset"
    private const val MASTER_KEY_URI = "android-keystore://masroof_session_master"

    fun aead(context: Context): Aead {
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
}
