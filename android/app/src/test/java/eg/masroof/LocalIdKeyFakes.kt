package eg.masroof

import android.util.Base64
import com.google.crypto.tink.Aead
import eg.masroof.ingest.KeyPrefs
import eg.masroof.ingest.KeyRenewedNotice
import eg.masroof.ingest.KeysetPort
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/** A simulated process death at a durable write; an Error on purpose, so nothing in the driver can swallow it. */
internal class KeyKilled : Error("killed")

internal class KeyFakeClock(
    var now: Long = 1_000_000_000_000L,
) {
    fun advance(millis: Long) {
        now += millis
    }
}

/** Numbers every durable write (prefs commits and keyset create/destroy) and can kill the process before or after one. */
internal class Writes {
    var count = 0
    var killAt: Int = -1
    var killAfter = false
    private var pendingAfterKill = false

    /** Called with the write about to happen. Throws [KeyKilled] before it (the write is lost) or returns a flag to kill after it. */
    fun begin(): Boolean {
        count++
        if (count == killAt && !killAfter) throw KeyKilled()
        return count == killAt && killAfter
    }
}

internal class FakePrefs(
    private val writes: Writes,
) : KeyPrefs {
    val map = mutableMapOf<String, String>()
    var failNextCommit = false
    var commits = 0

    override fun get(name: String): String? = map[name]

    override fun commit(
        puts: Map<String, String>,
        removes: Set<String>,
    ) {
        if (failNextCommit) {
            failNextCommit = false
            throw IOException("prefs commit failed")
        }
        val killAfter = writes.begin()
        commits++
        removes.forEach { map.remove(it) }
        map.putAll(puts)
        if (killAfter) throw KeyKilled()
    }

    override fun clear() {
        val killAfter = writes.begin()
        commits++
        map.clear()
        if (killAfter) throw KeyKilled()
    }
}

/** A fault script of a keyset: [make] gives the exception a build throws at a fake time, or null when it works. */
internal class Fault(
    val clearedByDestroy: Boolean = false,
    val make: (Long) -> Throwable?,
)

internal fun weak() = IllegalStateException("keystore busy")

internal fun keyGone() = UnrecoverableKeyException("Failed to obtain information about key")

internal fun corrupt() = java.io.CharConversionException("not a keyset")

/**
 * An in-memory keyset: a durable generation (0 = none; a build with 0 creates one, which is a durable write). Each generation has its
 * own AES-GCM key, so another generation gives a bad tag like a recreated keyset.
 */
internal class FakeKeyset(
    val name: String,
    private val writes: Writes,
    private val clock: KeyFakeClock,
    private val corroborating: (Throwable) -> Boolean = { it is UnrecoverableKeyException },
) : KeysetPort {
    var generation = 0
    private var next = 1
    private val aeads = mutableMapOf<Int, Aead>()
    var fault: Fault? = null
    var builds = 0
    var recreates = 0
    val destroyed = mutableListOf<Long>()

    fun aeadFor(gen: Int): Aead = aeads.getOrPut(gen) { inMemoryAead() }

    override fun build(fresh: Boolean): Aead {
        builds++
        fault?.make?.invoke(clock.now)?.let { throw it }
        if (generation == 0) {
            val killAfter = writes.begin()
            generation = next++
            if (killAfter) throw KeyKilled()
        }
        return aeadFor(generation)
    }

    override fun corroborates(e: Throwable): Boolean = corroborating(e)

    override fun recreate() = destroy()

    /** What Tink's reset or "Erase all data" does: the keyset is gone and the next build makes a working one. */
    fun destroy() {
        val killAfter = writes.begin()
        recreates++
        destroyed += clock.now
        next = maxOf(next, generation + 1)
        generation = 0
        if (fault?.clearedByDestroy == true) fault = null
        if (killAfter) throw KeyKilled()
    }
}

internal class RecordingNotice : KeyRenewedNotice {
    var posts = 0

    override fun post() {
        posts++
    }
}

internal fun sealUnder(
    aead: Aead,
    key: ByteArray,
): String = Base64.encodeToString(aead.encrypt(key, "masroof-local-id".toByteArray()), Base64.NO_WRAP)

/** Throwables the policy classifies; used by the matrix. */
internal val ALL_WEAK: List<() -> Throwable> =
    listOf(
        { IOException("disk") },
        { GeneralSecurityException("busy") },
        { ProviderException("binder died") },
        { weak() },
        { java.security.InvalidKeyException("Keystore cannot load the key") },
    )
