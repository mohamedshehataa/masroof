package eg.masroof.ingest.backfill

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import eg.masroof.ingest.HandledMemory
import eg.masroof.ingest.SignatureClaim
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Where a "Read past SMS" run stands. */
enum class BackfillPhase { IDLE, RUNNING, DONE, STOPPED, PERMISSION_LOST }

/**
 * One run of "Read past SMS": the range, the counts the progress line shows and the resume point. Counts and the position only; no
 * message text and no sender is kept here.
 */
data class BackfillState(
    val phase: BackfillPhase = BackfillPhase.IDLE,
    /** Which choice started the run (`D90`, `C20260801`); a restart with the same key continues instead of starting over. */
    val rangeKey: String = "",
    val fromMillis: Long = 0L,
    val toMillis: Long = 0L,
    /** Allowlisted messages in the range, counted before the first batch; -1 while not counted yet. */
    val total: Int = -1,
    val read: Int = 0,
    val entries: Int = 0,
    val review: Int = 0,
    val lastDateMillis: Long = -1L,
    val lastId: Long = -1L,
) {
    val position: InboxPosition? get() = if (lastDateMillis < 0) null else InboxPosition(lastDateMillis, lastId)

    /** Finished or not, the run has nothing to continue. */
    val resumable: Boolean get() =
        phase == BackfillPhase.RUNNING ||
            phase == BackfillPhase.STOPPED ||
            phase == BackfillPhase.PERMISSION_LOST
}

/**
 * The run state (observable) and the 30-day memory of which messages a backfill already handled. The memory holds only keyed
 * HMAC ids (never text) with the time they were handled, and is what keeps a deleted entry or a dismissed review item from coming
 * back when the user reads the same range again (REQ-SMS-102). It lives in its own preferences file, wiped by "Erase all data".
 */
class BackfillStore(
    private val prefs: SharedPreferences,
    /** Seals the remembered times (production: the Tink AEAD of the Android Keystore); null only in tests that read the file. */
    private val aead: Aead? = null,
) : HandledMemory {
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<BackfillState> = mutable

    /** Re-reads the file (after an erase, or when a worker in another process state changed it). */
    fun refresh() {
        mutable.value = read()
    }

    fun save(state: BackfillState) {
        prefs.edit {
            putString(PHASE, state.phase.name)
            putString(KEY, state.rangeKey)
            putLong(FROM, state.fromMillis)
            putLong(TO, state.toMillis)
            putInt(TOTAL, state.total)
            putInt(READ, state.read)
            putInt(ENTRIES, state.entries)
            putInt(REVIEW, state.review)
            putLong(LAST_DATE, state.lastDateMillis)
            putLong(LAST_ID, state.lastId)
        }
        mutable.value = state
    }

    /** Was this message handled in the last 30 days? (A deleted entry or a dismissed review item must not come back.) */
    fun handled(
        id: String,
        nowMillis: Long,
    ): Boolean =
        prefs
            .getString(SEEN + id, null)
            ?.let(::open)
            ?.toLongOrNull()
            ?.let { it >= nowMillis - MEMORY_MILLIS } == true

    /** Every mark also prunes what is older than 30 days, at most once a day (the scan reads every key). */
    override fun mark(
        id: String,
        nowMillis: Long,
    ) {
        pruneDaily(nowMillis)
        prefs.edit { putString(SEEN + id, seal(nowMillis.toString())) }
    }

    /** A signature with the times (of the message, of the mark) it was seen at; at most [MAX_TIMES] are kept per signature. */
    override fun markSignature(
        signature: String,
        atMillis: Long,
        nowMillis: Long,
    ) {
        pruneDaily(nowMillis)
        val kept = times(signature).filter { it.marked >= nowMillis - MEMORY_MILLIS }.takeLast(MAX_TIMES - 1)
        save(signature, kept + Seen(atMillis, nowMillis, false))
    }

    override fun seenSignature(
        signature: String,
        fromMillis: Long,
        toMillis: Long,
        nowMillis: Long,
    ): Boolean = times(signature).any { it.at in fromMillis..toMillis && it.marked >= nowMillis - MEMORY_MILLIS }

    override fun claimSignature(
        signature: String,
        fromMillis: Long,
        toMillis: Long,
        atMillis: Long,
        nowMillis: Long,
    ): SignatureClaim {
        val all = times(signature)
        val inWindow = all.filter { it.at in fromMillis..toMillis && it.marked >= nowMillis - MEMORY_MILLIS }
        if (inWindow.isEmpty()) return SignatureClaim.NONE
        val closest = inWindow.filter { !it.used }.minByOrNull { abs(it.at - atMillis) } ?: return SignatureClaim.ALREADY_CLAIMED
        save(signature, all.map { if (it === closest) it.copy(used = true) else it })
        return SignatureClaim.CLAIMED
    }

    /** What is remembered of one signature: when the message was, when it was marked, and whether a message already claimed it. */
    private data class Seen(
        val at: Long,
        val marked: Long,
        val used: Boolean,
    )

    private fun times(signature: String): List<Seen> =
        prefs
            .getString(SIG + signature, null)
            ?.let(::open)
            ?.split(',')
            ?.mapNotNull { part ->
                val f = part.split(':')
                val at = f.getOrNull(0)?.toLongOrNull()
                val marked = f.getOrNull(1)?.toLongOrNull()
                if (at == null || marked == null) null else Seen(at, marked, f.getOrNull(2) == "1")
            }.orEmpty()

    private fun save(
        signature: String,
        all: List<Seen>,
    ) {
        val text = all.joinToString(",") { "${it.at}:${it.marked}:${if (it.used) 1 else 0}" }
        prefs.edit { putString(SIG + signature, seal(text)) }
    }

    /** The values (times) are sealed with the keystore-backed AEAD when there is one: the file alone says nothing about when. */
    private fun seal(text: String): String =
        aead?.let { Base64.encodeToString(it.encrypt(text.toByteArray(Charsets.UTF_8), AAD), Base64.NO_WRAP) } ?: text

    private fun open(raw: String): String? {
        val cipher = aead ?: return raw
        return runCatching { String(cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP), AAD), Charsets.UTF_8) }.getOrNull()
    }

    /** After "Erase all data": the file is empty, so the screen goes back to the start instead of showing the erased run. */
    fun reset() {
        prefs.edit { clear() }
        mutable.value = BackfillState()
        prunedAt = null
    }

    private var prunedAt: Long? = null

    private fun pruneDaily(nowMillis: Long) {
        if (prunedAt?.let { nowMillis - it < PRUNE_EVERY_MILLIS } == true) return
        prunedAt = nowMillis
        forgetBefore(nowMillis - MEMORY_MILLIS)
    }

    /** Forgets what is older than [cutoffMillis] (30 days), inside a signature too: after that a deleted message may be read in again. */
    fun forgetBefore(cutoffMillis: Long) {
        val drop = mutableListOf<String>()
        val rewrite = mutableMapOf<String, List<Seen>>()
        for ((k, v) in prefs.all) {
            if (v !is String) continue
            when {
                k.startsWith(SEEN) -> if ((open(v)?.toLongOrNull() ?: Long.MIN_VALUE) < cutoffMillis) drop += k
                k.startsWith(SIG) -> {
                    val all = times(k.removePrefix(SIG))
                    val fresh = all.filter { it.marked >= cutoffMillis }
                    if (fresh.isEmpty()) {
                        drop += k
                    } else if (fresh.size < all.size) {
                        rewrite[k.removePrefix(SIG)] = fresh
                    }
                }
            }
        }
        if (drop.isNotEmpty()) prefs.edit { drop.forEach { remove(it) } }
        rewrite.forEach { (signature, fresh) -> save(signature, fresh) }
    }

    private fun read(): BackfillState =
        BackfillState(
            phase = prefs.getString(PHASE, null)?.let { n -> BackfillPhase.entries.firstOrNull { it.name == n } } ?: BackfillPhase.IDLE,
            rangeKey = prefs.getString(KEY, "").orEmpty(),
            fromMillis = prefs.getLong(FROM, 0L),
            toMillis = prefs.getLong(TO, 0L),
            total = prefs.getInt(TOTAL, -1),
            read = prefs.getInt(READ, 0),
            entries = prefs.getInt(ENTRIES, 0),
            review = prefs.getInt(REVIEW, 0),
            lastDateMillis = prefs.getLong(LAST_DATE, -1L),
            lastId = prefs.getLong(LAST_ID, -1L),
        )

    companion object {
        const val PREFS_NAME = "masroof_backfill"
        private const val SEEN = "seen:"
        private const val SIG = "sig:"
        private const val MAX_TIMES = 8
        private const val PRUNE_EVERY_MILLIS = 24L * 60 * 60 * 1000
        private val AAD = "masroof-backfill-memory".toByteArray()
        private const val MEMORY_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val PHASE = "phase"
        private const val KEY = "range_key"
        private const val FROM = "from"
        private const val TO = "to"
        private const val TOTAL = "total"
        private const val READ = "read"
        private const val ENTRIES = "entries"
        private const val REVIEW = "review"
        private const val LAST_DATE = "last_date"
        private const val LAST_ID = "last_id"
    }
}
