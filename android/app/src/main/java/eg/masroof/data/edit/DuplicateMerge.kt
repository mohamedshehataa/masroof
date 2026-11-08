package eg.masroof.data.edit

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AnswerPrefs
import eg.masroof.data.answers.DuplicateMatcher
import eg.masroof.data.answers.EntrySources
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.SourceChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** One "Possible duplicate" card: typed fields of both entries and where each came from. Never message text. */
data class DuplicateCard(
    val manual: ExpenseEntity,
    val capture: ExpenseEntity,
    val captureSources: EntrySources,
) {
    val key: String get() = DuplicateMatcher.pairKey(manual.id, capture.id)

    override fun toString() = "DuplicateCard(manual=${manual.id}, capture=${capture.id})"
}

/**
 * What one merge changed, so Undo puts back exactly that: both rows as they were (and as the merge left them), the sources and
 * receipts it moved, and the erase generation it was made in.
 */
data class MergeUndo(
    val manualBefore: ExpenseEntity,
    val manualAfter: ExpenseEntity,
    val captureBefore: ExpenseEntity,
    val survivor: ExpenseEntity,
    val sourceIds: List<String>,
    val receiptIds: List<String>,
    val epoch: Long,
) {
    override fun toString() = "MergeUndo(manual=${manualBefore.id}, capture=${captureBefore.id}, sources=${sourceIds.size})"
}

sealed interface MergeResult {
    data class Merged(
        val undo: MergeUndo,
    ) : MergeResult

    /** Either entry is gone, changed amount, is linked, or the pair was answered: nothing was merged. */
    data object OutOfDate : MergeResult

    /** The local write threw; both entries are unchanged. */
    data object Failed : MergeResult
}

/** REQ-DEDUP-063..065: the look-alike cards and the only write that merges two entries (always on a tap, never on its own). */
interface DuplicateRepository {
    fun observeCards(): Flow<List<DuplicateCard>>

    suspend fun merge(
        manualId: String,
        captureId: String,
    ): MergeResult

    /** "Keep both" (and a swipe-dismiss): remembered on this phone, so the pair never asks again. */
    suspend fun keepBoth(
        manualId: String,
        captureId: String,
    )

    /** Puts back both entries, their sources and receipts. False when an entry changed since, or after "Erase all data". */
    suspend fun undo(token: MergeUndo): Boolean
}

/** For code paths that have no duplicate cards (previews and older tests). */
object NoDuplicates : DuplicateRepository {
    override fun observeCards(): Flow<List<DuplicateCard>> = flowOf(emptyList())

    override suspend fun merge(
        manualId: String,
        captureId: String,
    ): MergeResult = MergeResult.OutOfDate

    override suspend fun keepBoth(
        manualId: String,
        captureId: String,
    ) = Unit

    override suspend fun undo(token: MergeUndo) = false
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** A manual entry may be back-dated, and the SMS backfill reads 90 days: both are looked at. */
internal const val DUPLICATE_LOOKBACK_DAYS = 100L

@Singleton
class DefaultDuplicateRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val prefs: AnswerPrefs,
        private val clock: Clock,
        private val eraseLock: EraseLock,
        private val signal: LocalDataSignal,
    ) : DuplicateRepository {
        override fun observeCards(): Flow<List<DuplicateCard>> {
            val since = clock.millis() - DUPLICATE_LOOKBACK_DAYS * DAY_MS
            // the STORY-182 server pairs have no age limit: their rows come from their own queries, merged in by id
            val serverRows = combine(db.expenses().observeServerPairRows(), db.sources().observeServerPairChannels()) { r, c -> r to c }
            return combine(
                db.expenses().observeLiveSince(since),
                db.sources().observeChannels(since),
                db.expenses().observeReferencedIds(),
                prefs.state,
                serverRows,
            ) { recent, recentChannels, referenced, state, (server, serverChannels) ->
                val blocked = state.linkedIds() + referenced
                val entries = (recent + server).distinctBy { it.id }
                val channels = (recentChannels + serverChannels).distinctBy { it.id }
                DuplicateMatcher
                    .pairs(entries, group(channels), blocked, state.keptApart)
                    .map { DuplicateCard(it.manual, it.capture, it.captureSources) }
            }
        }

        override suspend fun keepBoth(
            manualId: String,
            captureId: String,
        ) {
            val key = DuplicateMatcher.pairKey(manualId, captureId)
            eraseLock.mutex.withLock { prefs.edit { it.copy(keptApart = it.keptApart + key) } }
            signal.changed()
        }

        override suspend fun merge(
            manualId: String,
            captureId: String,
        ): MergeResult {
            val result =
                try {
                    eraseLock.mutex.withLock {
                        val state = prefs.current()
                        val epoch = prefs.epoch
                        // an entry that was merged before keeps its mark whatever this attempt does
                        val already = manualId in state.mergedAway
                        // marked first: a crash after the commit must never leave a restorable, source-less typed entry
                        prefs.edit { it.copy(mergedAway = it.mergedAway + manualId) }
                        val r =
                            try {
                                db.withTransaction { mergeLocked(manualId, captureId, state.linkedIds(), state.keptApart, epoch) }
                            } catch (e: SQLiteException) {
                                if (!already) unmark(manualId)
                                throw e
                            } catch (e: IllegalStateException) {
                                if (!already) unmark(manualId)
                                throw e
                            }
                        if (r !is MergeResult.Merged && !already) unmark(manualId)
                        r
                    }
                } catch (_: SQLiteException) {
                    MergeResult.Failed
                } catch (_: IllegalStateException) {
                    MergeResult.Failed
                }
            if (result is MergeResult.Merged) signal.changed()
            return result
        }

        private suspend fun unmark(manualId: String) = prefs.edit { it.copy(mergedAway = it.mergedAway - manualId) }

        /** Re-reads both entries and checks the whole rule again: a card that went stale merges nothing. */
        private suspend fun mergeLocked(
            manualId: String,
            captureId: String,
            linked: Set<String>,
            keptApart: Set<String>,
            epoch: Long,
        ): MergeResult {
            val manual = db.expenses().find(manualId)
            val capture = db.expenses().find(captureId)
            if (manual == null || capture == null) return MergeResult.OutOfDate
            val manualSources = db.sources().forExpense(manualId)
            val captureSources = db.sources().forExpense(captureId)
            val blocked = linked + db.expenses().referencedIds()
            val ms = sourcesOf(manualSources)
            val cs = sourcesOf(captureSources)
            // the STORY-173 pair rule, or the STORY-182 server-row rule (the local entry is `manual`, the imported row `capture`)
            val serverPair = DuplicateMatcher.matchesServer(manual, ms, capture, cs, blocked)
            val current =
                DuplicateMatcher.pairKey(manualId, captureId) !in keptApart &&
                    (serverPair || DuplicateMatcher.matches(manual, ms, capture, cs, blocked))
            if (!current) return MergeResult.OutOfDate
            val now = clock.millis()
            // the capture's amount, time, last four and method stay; the typed category, notes and paid-by fill what it has not
            val survivor =
                capture.copy(
                    categoryId = capture.categoryId ?: manual.categoryId,
                    merchant = capture.merchant ?: manual.merchant,
                    notes = capture.notes ?: manual.notes,
                    paidByMemberId = capture.paidByMemberId ?: manual.paidByMemberId,
                    // an imported row held for this decision is settled by it (a plain capture keeps the status it had)
                    status = if (serverPair) "CONFIRMED" else capture.status,
                    updatedAtMillis = now,
                )
            val receiptIds = db.receipts().idsOf(manualId)
            db.expenses().update(survivor)
            db.sources().repoint(manualSources.map { it.id }, captureId)
            receiptIds.forEach { db.receipts().moveTo(it, captureId) }
            db.expenses().softDelete(manualId, now)
            val undo =
                MergeUndo(
                    manualBefore = manual,
                    manualAfter = manual.copy(deletedAtMillis = now, updatedAtMillis = now),
                    captureBefore = capture,
                    survivor = survivor,
                    sourceIds = manualSources.map { it.id },
                    receiptIds = receiptIds,
                    epoch = epoch,
                )
            return MergeResult.Merged(undo)
        }

        override suspend fun undo(token: MergeUndo): Boolean {
            val ok =
                try {
                    eraseLock.mutex.withLock {
                        if (token.epoch != prefs.epoch) return@withLock false
                        // an Undo applies only in the state it was made in: a link made since (STORY-208) owns the survivor now
                        val linked = prefs.current().linkedIds()
                        if (token.captureBefore.id in linked || token.manualBefore.id in linked) return@withLock false
                        val done = db.withTransaction { undoLocked(token) }
                        if (done) unmark(token.manualBefore.id)
                        done
                    }
                } catch (_: SQLiteException) {
                    false
                } catch (_: IllegalStateException) {
                    false
                }
            if (ok) signal.changed()
            return ok
        }

        /** Only when both rows are exactly as the merge left them: an edit, a delete or an answer since means the Undo is gone. */
        private suspend fun undoLocked(
            token: MergeUndo,
        ): Boolean {
            val referenced = db.expenses().referencedIds()
            if (token.captureBefore.id in referenced || token.manualBefore.id in referenced) return false
            val manual = db.expenses().find(token.manualBefore.id)
            val capture = db.expenses().find(token.captureBefore.id)
            if (manual != token.manualAfter || capture != token.survivor) return false
            val onSurvivor = db.sources().forExpense(capture.id).mapTo(HashSet()) { it.id }
            if (!onSurvivor.containsAll(token.sourceIds)) return false
            db.expenses().update(token.captureBefore)
            db.expenses().update(token.manualBefore)
            db.sources().repoint(token.sourceIds, manual.id)
            val stillThere = db.receipts().idsOf(capture.id).toSet()
            token.receiptIds.filter { it in stillThere }.forEach { db.receipts().moveTo(it, manual.id) }
            return true
        }

        private fun sourcesOf(rows: List<eg.masroof.data.db.ExpenseSourceEntity>) =
            EntrySources(rows.mapTo(HashSet()) { it.channel }, rows.firstNotNullOfOrNull { it.senderId })

        private fun group(rows: List<SourceChannel>): Map<String, EntrySources> =
            rows.groupBy { it.expenseId }.mapValues { (_, list) ->
                EntrySources(list.mapTo(HashSet()) { it.channel }, list.firstNotNullOfOrNull { it.senderId })
            }
    }
