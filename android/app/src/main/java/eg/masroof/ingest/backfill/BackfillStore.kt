package eg.masroof.ingest.backfill

import android.content.SharedPreferences
import androidx.core.content.edit
import eg.masroof.ingest.HandledMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    fun handled(id: String): Boolean = prefs.contains(SEEN + id)

    /** Every mark also prunes what is older than 30 days, so the file only ever holds a month of keys (bounded work). */
    override fun mark(
        id: String,
        nowMillis: Long,
    ) {
        forgetBefore(nowMillis - MEMORY_MILLIS)
        prefs.edit { putLong(SEEN + id, nowMillis) }
    }

    /** A signature with the times (of the message, of the mark) it was seen at; at most [MAX_TIMES] are kept per signature. */
    override fun markSignature(
        signature: String,
        atMillis: Long,
        nowMillis: Long,
    ) {
        val kept = times(signature).filter { it.second >= nowMillis - MEMORY_MILLIS }.takeLast(MAX_TIMES - 1)
        val all = kept + (atMillis to nowMillis)
        prefs.edit { putString(SIG + signature, all.joinToString(",") { "${it.first}:${it.second}" }) }
    }

    override fun seenSignature(
        signature: String,
        fromMillis: Long,
        toMillis: Long,
    ): Boolean = times(signature).any { it.first in fromMillis..toMillis }

    private fun times(signature: String): List<Pair<Long, Long>> =
        prefs
            .getString(SIG + signature, null)
            ?.split(',')
            ?.mapNotNull { part ->
                val (at, marked) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val a = at.toLongOrNull() ?: return@mapNotNull null
                val m = marked.toLongOrNull() ?: return@mapNotNull null
                a to m
            }.orEmpty()

    /** After "Erase all data": the file is empty, so the screen goes back to the start instead of showing the erased run. */
    fun reset() {
        prefs.edit { clear() }
        mutable.value = BackfillState()
    }

    /** Forgets handled ids older than [cutoffMillis] (30 days): after that a deleted message may be read in again. */
    fun forgetBefore(cutoffMillis: Long) {
        val old =
            prefs.all
                .filter { (k, v) ->
                    when {
                        k.startsWith(SEEN) -> v is Long && v < cutoffMillis
                        k.startsWith(SIG) -> v is String && times(k.removePrefix(SIG)).all { it.second < cutoffMillis }
                        else -> false
                    }
                }.keys
        if (old.isNotEmpty()) prefs.edit { old.forEach { remove(it) } }
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
