package eg.masroof.ingest

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import eg.masroof.data.auth.TinkKeystore
import eg.masroof.ingest.parse.IncomingMessage
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Where "Erase all data" stands. Persisted, so a crash mid-erase is recognised at the next start. */
enum class EraseStage {
    NONE,

    /** Set before the database transaction: it may or may not have committed. Recovery reruns the whole (idempotent) wipe. */
    ERASING,

    /** Set right after the commit: the tables are final. Recovery runs the clean-up only and never touches tables. */
    CLEANUP,
}

/**
 * The erase-in-progress flag (ADR-0009 D3). Capture no longer depends on a session: it is open unless "Erase all data" is
 * running ([EraseStage] is not NONE). The receiver, the listener and the pipeline's write transaction all check it, so a job
 * that was in flight during the erase writes nothing. The per-channel "capture enabled" switches (SMS in
 * [SenderSettings], notifications in [NotificationSettings]) are separate and are re-checked in the write transaction too.
 */
interface CaptureGate {
    fun isOpen(): Boolean

    fun open()

    fun close()

    fun stage(): EraseStage = if (isOpen()) EraseStage.NONE else EraseStage.ERASING

    /** Persists the stage; false when the write failed (the caller treats that as an error). */
    fun setStage(stage: EraseStage): Boolean {
        if (stage == EraseStage.NONE) open() else close()
        return true
    }
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
    override fun isOpen() = stage() == EraseStage.NONE

    override fun stage(): EraseStage =
        prefs.getString(STAGE, null)?.let { name -> EraseStage.entries.firstOrNull { it.name == name } } ?: EraseStage.NONE

    @SuppressLint("UseKtx") // the KTX edit() hides the commit() result, which this must return
    override fun setStage(stage: EraseStage): Boolean =
        prefs
            .edit()
            .putString(STAGE, stage.name)
            .commit()

    override fun open() {
        setStage(EraseStage.NONE)
    }

    override fun close() {
        setStage(EraseStage.ERASING)
    }

    private companion object {
        // A new key: the old "capture_open" flag followed the session and must not decide anything any more.
        const val STAGE = "erase_stage"
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

    /**
     * Seals [key] (a restored backup's local-id key) under THIS phone's Keystore (STORY-184). Same keyset as [get] today. Crash-safe:
     * the new sealed copy must decrypt to the same bytes BEFORE it replaces the stored one, and the replace is one atomic commit, so
     * there is never a moment without a decryptable key and a failure leaves the old key in place (restore recovery repeats this).
     */
    fun install(key: ByteArray) = installWith(TinkKeystore.aead(context), key)

    @Synchronized
    internal fun installWith(
        aead: com.google.crypto.tink.Aead,
        key: ByteArray,
    ) {
        val sealed = aead.encrypt(key, AAD)
        val check = aead.decrypt(sealed, AAD)
        try {
            check(check.contentEquals(key)) { "sealed key does not round trip" }
        } finally {
            check.fill(0)
        }
        @SuppressLint("UseKtx") // the KTX edit() hides the commit() result, which decides whether the key is installed
        val ok = prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
        check(ok) { "could not store the sealed key" }
    }

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
