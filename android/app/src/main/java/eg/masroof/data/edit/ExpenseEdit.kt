package eg.masroof.data.edit

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AnswerWrites
import eg.masroof.data.answers.NoPairLookup
import eg.masroof.data.answers.PairLookup
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.MemberEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fields the edit screen changes; null = leave as it is. Money is minor units + currency, never a float. A cleared
 * note or merchant is an empty string (stored as null). [paidByChanged] tells "set to Not set" ([paidByMemberId] null)
 * from "unchanged".
 */
data class ExpenseChanges(
    val type: String? = null,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val occurredAtMillis: Long? = null,
    val categoryId: String? = null,
    val merchantRaw: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val paidByMemberId: String? = null,
    val paidByChanged: Boolean = false,
    /** "Don't count in totals" (REQ-AN-175); null = unchanged. */
    val excludedFromTotals: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = this == ExpenseChanges()

    // Amount, merchant and notes are private: never printed.
    override fun toString() = "ExpenseChanges(type=$type, status=$status)"
}

/** Frozen seam over the generated ExpensesApi, kept for the one-time server import only; the edit screen no longer calls it. */
interface ExpenseEditRemote {
    /** @throws IOException when unreachable (or past the deadline), HttpFailure on a non-2xx answer. */
    suspend fun get(id: String): ExpenseEntity

    suspend fun patch(
        id: String,
        changes: ExpenseChanges,
    ): ExpenseEntity

    suspend fun delete(id: String)
}

sealed interface EditOutcome {
    data class Saved(
        val expense: ExpenseEntity,
    ) : EditOutcome

    data object Deleted : EditOutcome

    /** The entry is gone (soft-deleted or missing) while the screen was open; nothing was written. */
    data object NotFound : EditOutcome

    /** The local write threw; the row is unchanged. */
    data object Failed : EditOutcome
}

interface ExpenseEditor {
    /** The entry, unless it is missing or soft-deleted. */
    suspend fun find(id: String): ExpenseEntity?

    fun observeCategories(): Flow<List<CategoryEntity>>

    fun observeMembers(): Flow<List<MemberEntity>>

    /** Ids of entries soft-deleted from the edit screen, for the "Deleted - Undo" snackbar. */
    val deleted: SharedFlow<String>

    suspend fun update(
        id: String,
        changes: ExpenseChanges,
    ): EditOutcome

    /** Soft delete (ADR-0009 D3); [restore] brings it back. */
    suspend fun delete(id: String): EditOutcome

    /** STORY-208: deletes the entry and the other side of its linked transfer pair together; [restore] brings both back. */
    suspend fun deleteWithPartner(id: String): EditOutcome = delete(id)

    /** Undo of [delete]. Returns false when there was nothing to restore. */
    suspend fun restore(id: String): Boolean
}

private const val DELETION_BUFFER = 16

/** Edit, confirm, delete and Undo on Room only; no HTTP. Every write waits for a running erase and is one transaction. */
@Singleton
class DefaultExpenseEditor
    @Inject
    constructor(
        private val db: AppDatabase,
        private val signal: LocalDataSignal,
        private val clock: Clock,
        private val eraseLock: EraseLock,
        private val pairs: PairLookup = NoPairLookup,
    ) : ExpenseEditor {
        private val deletions = MutableSharedFlow<String>(extraBufferCapacity = DELETION_BUFFER)

        override val deleted: SharedFlow<String> = deletions

        override suspend fun find(id: String) = db.expenses().find(id)?.takeIf { it.deletedAtMillis == null }

        override fun observeCategories() = db.lookups().observeLabelCategories()

        override fun observeMembers() = db.lookups().observeMembers()

        override suspend fun update(
            id: String,
            changes: ExpenseChanges,
        ): EditOutcome =
            guarded {
                val now = clock.millis()
                val links = if (changes.status != null) pairs.linksOf(id) else emptyList()
                val outcome =
                    eraseLock.mutex.withLock {
                        db.withTransaction {
                            val old = db.expenses().find(id)
                            when {
                                old == null || old.deletedAtMillis != null -> EditOutcome.NotFound
                                changes.isEmpty -> EditOutcome.Saved(old)
                                else -> {
                                    val updated = old.withChanges(changes, now)
                                    db.expenses().update(updated)
                                    // confirming a pending withdrawal confirms the cash side the app added for it
                                    if (old.status != updated.status) {
                                        links.mapNotNull { it.deletablePartnerOf(id) }.forEach { partner ->
                                            val row = db.expenses().find(partner)
                                            if (row != null && row.status == old.status) {
                                                db.expenses().update(row.copy(status = updated.status, updatedAtMillis = now))
                                            }
                                        }
                                    }
                                    EditOutcome.Saved(updated)
                                }
                            }
                        }
                    }
                if (outcome is EditOutcome.Saved && !changes.isEmpty) signal.changed()
                outcome
            }

        /** Deletes this entry only; a converted real entry is put back as it was, a refund of this purchase becomes income. */
        override suspend fun delete(id: String): EditOutcome = remove(id, withPartner = false)

        override suspend fun deleteWithPartner(id: String): EditOutcome = remove(id, withPartner = true)

        private suspend fun remove(
            id: String,
            withPartner: Boolean,
        ): EditOutcome =
            guarded {
                val links = pairs.linksOf(id)
                val changed =
                    eraseLock.mutex.withLock {
                        db.withTransaction {
                            // one timestamp for both: Undo restores the partner only if it went away in the same moment
                            val now = clock.millis()
                            val first = db.expenses().softDelete(id, now)
                            if (first > 0) AnswerWrites.release(db, links, id, withPartner, now)
                            first
                        }
                    }
                if (changed == 0) {
                    EditOutcome.NotFound
                } else {
                    signal.changed()
                    deletions.tryEmit(id)
                    EditOutcome.Deleted
                }
            }

        override suspend fun restore(id: String): Boolean {
            val partners = pairs.linksOf(id).mapNotNull { it.deletablePartnerOf(id) }
            val changed =
                try {
                    eraseLock.mutex.withLock {
                        db.withTransaction {
                            val deletedAt = db.expenses().find(id)?.deletedAtMillis
                            val first = db.expenses().restore(id, clock.millis())
                            if (first > 0) {
                                partners.filter { db.expenses().find(it)?.deletedAtMillis == deletedAt }.forEach {
                                    db.expenses().restore(it, clock.millis())
                                }
                            }
                            first
                        }
                    }
                } catch (_: SQLiteException) {
                    0
                } catch (_: IllegalStateException) {
                    0
                }
            if (changed > 0) signal.changed()
            return changed > 0
        }

        // A local write that throws leaves the row as it was (the transaction rolled back); nothing is logged.
        private suspend fun guarded(block: suspend () -> EditOutcome): EditOutcome =
            try {
                block()
            } catch (_: SQLiteException) {
                EditOutcome.Failed
            } catch (_: IllegalStateException) {
                EditOutcome.Failed
            }
    }

/** Only the columns the user changed, plus a new `updatedAtMillis`; saving a pending-review entry confirms it. */
internal fun ExpenseEntity.withChanges(
    c: ExpenseChanges,
    now: Long,
): ExpenseEntity {
    val dateChanged = c.occurredAtMillis != null && c.occurredAtMillis != occurredAtMillis
    return copy(
        type = c.type ?: type,
        amountMinor = c.amountMinor ?: amountMinor,
        currency = c.currency ?: currency,
        occurredAtMillis = c.occurredAtMillis ?: occurredAtMillis,
        timeKnown = if (dateChanged) false else timeKnown,
        categoryId = c.categoryId ?: categoryId,
        merchant = if (c.merchantRaw != null) c.merchantRaw.ifBlank { null } else merchant,
        notes = if (c.notes != null) c.notes.ifBlank { null } else notes,
        status = c.status ?: status,
        paidByMemberId = if (c.paidByChanged) c.paidByMemberId else paidByMemberId,
        excludedFromTotals = c.excludedFromTotals ?: excludedFromTotals,
        updatedAtMillis = now,
    )
}
