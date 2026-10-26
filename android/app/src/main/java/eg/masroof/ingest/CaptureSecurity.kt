package eg.masroof.ingest

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import eg.masroof.data.auth.TinkKeystore
import eg.masroof.ingest.parse.IncomingMessage
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Capture only runs while somebody is signed in. The receiver and the notification listener are system-started, so
 * the flag lives in SharedPreferences (readable synchronously and before the session is restored). Logout closes it
 * first, sign-in opens it, so a message arriving between two accounts is dropped instead of uploading into the next one.
 */
interface CaptureGate {
    fun isOpen(): Boolean

    fun open()

    fun close()
}

/** Used where no capture exists (unit tests of the session). */
object NoopCaptureGate : CaptureGate {
    override fun isOpen() = false

    override fun open() = Unit

    override fun close() = Unit
}

class PrefsCaptureGate(
    private val prefs: SharedPreferences,
) : CaptureGate {
    override fun isOpen() = prefs.getBoolean(OPEN, false)

    override fun open() = prefs.edit(commit = true) { putBoolean(OPEN, true) }

    override fun close() = prefs.edit(commit = true) { putBoolean(OPEN, false) }

    private companion object {
        const val OPEN = "capture_open"
    }
}

/** Per-installation secret that keys the local message id. Never uploaded; wiped on logout. */
interface LocalIdKey {
    fun get(): ByteArray

    fun wipe()
}

/** 32 random bytes, sealed with a Tink AEAD whose key lives in the Android Keystore. */
class KeystoreLocalIdKey(
    private val context: Context,
) : LocalIdKey {
    private val prefs get() = context.getSharedPreferences("masroof_capture_key", Context.MODE_PRIVATE)

    @Synchronized
    override fun get(): ByteArray {
        val aead = TinkKeystore.aead(context)
        prefs.getString(KEY, null)?.let { stored ->
            runCatching { return aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), AAD) }
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit(commit = true) { putString(KEY, Base64.encodeToString(aead.encrypt(fresh, AAD), Base64.NO_WRAP)) }
        return fresh
    }

    @Synchronized
    override fun wipe() = prefs.edit(commit = true) { clear() }

    private companion object {
        const val KEY = "k"
        const val KEY_BYTES = 32
        val AAD = "masroof-local-id".toByteArray()
    }
}

/**
 * The stable local id of a message: HMAC-SHA256 over sender, sent second and text with the installation key. An unkeyed
 * hash of a short, structured bank SMS could be brute-forced from the database (recovering balances); this cannot.
 */
object LocalIdHasher {
    private const val MILLIS_PER_SECOND = 1000L

    fun id(
        key: ByteArray,
        msg: IncomingMessage,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val material = "${senderKey(msg.sender)}|${msg.receivedAtMillis / MILLIS_PER_SECOND}|${msg.text}"
        return mac.doFinal(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Everything capture keeps outside Room, cleared with the rest on logout and account switch. */
interface CaptureData {
    fun clear()
}

object NoCaptureData : CaptureData {
    override fun clear() = Unit
}

class PrefsCaptureData(
    private val context: Context,
    private val key: LocalIdKey,
) : CaptureData {
    override fun clear() {
        for (name in listOf("masroof_senders", "masroof_notifications", "masroof_device")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        }
        key.wipe()
    }
}
