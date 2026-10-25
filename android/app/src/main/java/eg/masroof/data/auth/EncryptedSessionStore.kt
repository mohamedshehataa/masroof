package eg.masroof.data.auth

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException

/** Seals/opens bytes. Production uses Tink AEAD under an Android Keystore master key; unit tests an in-memory key. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws GeneralSecurityException when the ciphertext cannot be authenticated or the key is gone. */
    fun decrypt(sealed: ByteArray): ByteArray
}

class AeadTokenCipher(
    private val aead: Aead,
) : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = aead.encrypt(plain, ASSOCIATED_DATA)

    override fun decrypt(sealed: ByteArray): ByteArray = aead.decrypt(sealed, ASSOCIATED_DATA)

    private companion object {
        val ASSOCIATED_DATA = "masroof.session.v1".toByteArray()
    }
}

/**
 * The session as one AEAD-sealed blob in SharedPreferences; nothing is stored or logged in clear text. commit() on
 * purpose: a rotated refresh token must be durable before the old one is forgotten (callers run on IO).
 */
@SuppressLint("ApplySharedPref")
class EncryptedSessionStore(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher,
) : SessionStore {
    override fun load(): LoadedSession {
        val encoded = prefs.getString(KEY_SESSION, null) ?: return LoadedSession.Empty
        return try {
            val plain = cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP))
            LoadedSession.Found(Json.decodeFromString(Session.serializer(), plain.decodeToString()))
        } catch (_: GeneralSecurityException) {
            discard()
        } catch (_: IOException) {
            discard()
        } catch (_: SerializationException) {
            discard()
        } catch (_: IllegalArgumentException) {
            discard()
        }
    }

    private fun discard(): LoadedSession {
        clear()
        return LoadedSession.Invalidated
    }

    override fun save(session: Session) {
        val plain = Json.encodeToString(Session.serializer(), session).toByteArray()
        val sealed = Base64.encodeToString(cipher.encrypt(plain), Base64.NO_WRAP)
        // commit(): a rotated refresh token must be on disk before the old one is forgotten.
        prefs.edit(commit = true) { putString(KEY_SESSION, sealed) }
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(KEY_SESSION) }
    }

    override var lastMemberId: String?
        get() = prefs.getString(KEY_LAST_MEMBER, null)
        set(value) {
            prefs.edit(commit = true) { if (value == null) remove(KEY_LAST_MEMBER) else putString(KEY_LAST_MEMBER, value) }
        }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_LAST_MEMBER = "last_member"
    }
}

/** Defers building the Keystore-backed cipher until first use. */
class LazyTokenCipher(
    factory: () -> TokenCipher,
) : TokenCipher {
    private val delegate by lazy(factory)

    override fun encrypt(plain: ByteArray) = delegate.encrypt(plain)

    override fun decrypt(sealed: ByteArray) = delegate.decrypt(sealed)
}
