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
    /** Seals the remembered times (production: the phone's own `LocalKeystore`, never destroyed by logout); null only in tests. */
    private val aead: Aead? = null,
) : HandledMemory {
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<BackfillState> = mutable

    /** Re-reads the file (after an erase, or when a worker in another process state changed it). */
    fun refresh() {
        mutable.value = read()
    }

    fun save(state: BackfillState) {
        // the times of the run (range, last message read) are sealed like the memory; a value that cannot be sealed is not written
        val from = seal(state.fromMillis.toString())
        val to = seal(state.toMillis.toString())
        val last = seal(state.lastDateMillis.toString())
        prefs.edit {
            putString(PHASE, state.phase.name)
            putString(KEY, state.rangeKey)
            from?.let { putString(FROM, it) }
            to?.let { putString(TO, it) }
            putInt(TOTAL, state.total)
            putInt(READ, state.read)
            putInt(ENTRIES, state.entries)
            putInt(REVIEW, state.review)
            last?.let { putString(LAST_DATE, it) }
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
        seal(nowMillis.toString())?.let { sealed -> prefs.edit { putString(SEEN + id, sealed) } }
    }

    /**
     * A signature with the times (of the message, of the mark) it was seen at; at most [MAX_TIMES] are kept per signature. A value that
     * cannot be opened (a Keystore error that may pass) is never overwritten or deleted: the new time goes to a second key that is
     * merged back when the first one opens again.
     */
    override fun markSignature(
        signature: String,
        atMillis: Long,
        nowMillis: Long,
    ) {
        pruneDaily(nowMillis)
        val fresh = Seen(atMillis, nowMillis, false)
        val live = { l: List<Seen> -> l.filter { it.marked >= nowMillis - MEMORY_MILLIS }.takeLast(MAX_TIMES - 1) }
        val main = slot(SIG + signature)
        val aux = slot(AUX + signature)
        if (main != null) {
            val saved = write(SIG + signature, live(main + aux.orEmpty()) + fresh)
            if (saved && aux != null) prefs.edit { remove(AUX + signature) }
        } else if (aux != null) {
            write(AUX + signature, live(aux) + fresh)
        }
    }

    override fun seenSignature(
        signature: String,
        fromMillis: Long,
        toMillis: Long,
        nowMillis: Long,
    ): Boolean =
        (slot(SIG + signature).orEmpty() + slot(AUX + signature).orEmpty())
            .any { it.at in fromMillis..toMillis && it.marked >= nowMillis - MEMORY_MILLIS }

    override fun claimSignature(
        signature: String,
        fromMillis: Long,
        toMillis: Long,
        atMillis: Long,
        nowMillis: Long,
    ): SignatureClaim {
        val parts = listOf(SIG + signature, AUX + signature).mapNotNull { k -> slot(k)?.let { k to it } }
        val inWindow =
            parts
                .flatMap { (k, l) -> l.map { k to it } }
                .filter { (_, t) -> t.at in fromMillis..toMillis && t.marked >= nowMillis - MEMORY_MILLIS }
        if (inWindow.isEmpty()) return SignatureClaim.NONE
        val closest =
            inWindow.filter { !it.second.used }.minByOrNull { abs(it.second.at - atMillis) } ?: return SignatureClaim.ALREADY_CLAIMED
        val list = parts.first { it.first == closest.first }.second
        write(closest.first, list.map { if (it === closest.second) it.copy(used = true) else it })
        return SignatureClaim.CLAIMED
    }

    /** What is remembered of one signature: when the message was, when it was marked, and whether a message already claimed it. */
    private data class Seen(
        val at: Long,
        val marked: Long,
        val used: Boolean,
    )

    /** The times under one key: empty when there is none, null when there is a value that cannot be opened (never touch it). */
    private fun slot(key: String): List<Seen>? {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val text = open(raw) ?: return null
        return text.split(',').mapNotNull { part ->
            val f = part.split(':')
            val at = f.getOrNull(0)?.toLongOrNull()
            val marked = f.getOrNull(1)?.toLongOrNull()
            if (at == null || marked == null) null else Seen(at, marked, f.getOrNull(2) == "1")
        }
    }

    /** Writes the times under [key] (removes it when none are left); false when they could not be sealed (nothing is written then). */
    private fun write(
        key: String,
        all: List<Seen>,
    ): Boolean {
        if (all.isEmpty()) {
            prefs.edit { remove(key) }
            return true
        }
        val sealed = seal(all.joinToString(",") { "${it.at}:${it.marked}:${if (it.used) 1 else 0}" }) ?: return false
        prefs.edit { putString(key, sealed) }
        return true
    }

    /** The values (times) are sealed with the keystore-backed AEAD when there is one: the file alone says nothing about when. */
    private fun seal(text: String): String? {
        val cipher = aead ?: return text
        // a key that cannot seal (and could not be recreated) means nothing is remembered; it never crashes a capture or a run
        return runCatching { Base64.encodeToString(cipher.encrypt(text.toByteArray(Charsets.UTF_8), AAD), Base64.NO_WRAP) }.getOrNull()
    }

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

    /** The key was provably lost and recreated: nothing in the memory can ever be opened again, so all of it goes at once (bounded). */
    fun clearMemory() {
        val keys = prefs.all.keys.filter { it.startsWith(SEEN) || it.startsWith(SIG) || it.startsWith(AUX) }
        if (keys.isNotEmpty()) prefs.edit { keys.forEach { remove(it) } }
    }

    private var prunedAt: Long? = null

    private fun pruneDaily(nowMillis: Long) {
        if (prunedAt?.let { nowMillis - it < PRUNE_EVERY_MILLIS } == true) return
        prunedAt = nowMillis
        forgetBefore(nowMillis - MEMORY_MILLIS)
    }

    /**
     * Forgets what is older than [cutoffMillis] (30 days), inside a signature too: after that a deleted message may be read in again.
     * A value that cannot be opened is left alone (it may open again); only [clearMemory] removes those, when the key is provably lost.
     */
    fun forgetBefore(cutoffMillis: Long) {
        val drop = mutableListOf<String>()
        val rewrite = mutableMapOf<String, List<Seen>>()
        for ((k, v) in prefs.all) {
            if (v !is String) continue
            when {
                k.startsWith(SEEN) -> if ((open(v)?.toLongOrNull() ?: Long.MAX_VALUE) < cutoffMillis) drop += k
                k.startsWith(SIG) || k.startsWith(AUX) -> {
                    val all = slot(k) ?: continue
                    val fresh = all.filter { it.marked >= cutoffMillis }
                    if (fresh.isEmpty()) {
                        drop += k
                    } else if (fresh.size < all.size) {
                        rewrite[k] = fresh
                    }
                }
            }
        }
        if (drop.isNotEmpty()) prefs.edit { drop.forEach { remove(it) } }
        rewrite.forEach { (key, fresh) -> write(key, fresh) }
    }

    private fun read(): BackfillState =
        BackfillState(
            phase = prefs.getString(PHASE, null)?.let { n -> BackfillPhase.entries.firstOrNull { it.name == n } } ?: BackfillPhase.IDLE,
            rangeKey = prefs.getString(KEY, "").orEmpty(),
            fromMillis = sealedLong(FROM, 0L),
            toMillis = sealedLong(TO, 0L),
            total = prefs.getInt(TOTAL, -1),
            read = prefs.getInt(READ, 0),
            entries = prefs.getInt(ENTRIES, 0),
            review = prefs.getInt(REVIEW, 0),
            lastDateMillis = sealedLong(LAST_DATE, -1L),
            lastId = prefs.getLong(LAST_ID, -1L),
        )

    private fun sealedLong(
        key: String,
        default: Long,
    ): Long = prefs.getString(key, null)?.let(::open)?.toLongOrNull() ?: default

    companion object {
        const val PREFS_NAME = "masroof_backfill"
        private const val SEEN = "seen:"
        private const val SIG = "sig:"
        private const val AUX = "sig2:"
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
