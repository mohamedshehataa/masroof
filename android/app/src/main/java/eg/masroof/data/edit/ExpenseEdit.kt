package eg.masroof.data.edit

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AnswerWrites
import eg.masroof.data.answers.EntryLink
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

    /** The row is part of a live pair or refund link: its amount, currency, type and date are locked until it is unlinked. */
    data object LinkedRefund : EditOutcome
}

enum class RestoreOutcome { Restored, NothingToRestore, MergedAway }

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

    /** [restore] with the reason when nothing came back: a typed entry that a merge folded into a capture is never restored. */
    suspend fun restoreOutcome(id: String): RestoreOutcome = if (restore(id)) RestoreOutcome.Restored else RestoreOutcome.NothingToRestore
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
                val links = pairs.linksOf(id)
                val outcome =
                    eraseLock.mutex.withLock {
                        db.withTransaction {
                            val old = db.expenses().find(id)
                            when {
                                old == null || old.deletedAtMillis != null -> EditOutcome.NotFound
                                changes.isEmpty -> EditOutcome.Saved(old)
                                lockedByLink(old, changes, links) -> EditOutcome.LinkedRefund
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
                                    // a linked row's fields follow the link (a purchase's new category moves its refund)
                                    AnswerWrites.reconcile(db, links)
                                    EditOutcome.Saved(updated)
                                }
                            }
                        }
                    }
                if (outcome is EditOutcome.Saved && !changes.isEmpty) signal.changed()
                outcome
            }

        /**
         * Rule 1: while a row is part of a live pair or a live refund link, its money fields (amount, currency, type, date) are
         * locked: changing them would leave the other side with phantom money. Notes, merchant and category stay editable (a
         * purchase's category moves its refund). "Unlink" releases the row.
         */
        private suspend fun lockedByLink(
            old: ExpenseEntity,
            changes: ExpenseChanges,
            links: List<EntryLink>,
        ): Boolean {
            val touchesMoney =
                (changes.amountMinor != null && changes.amountMinor != old.amountMinor) ||
                    (changes.currency != null && changes.currency != old.currency) ||
                    (changes.type != null && changes.type != old.type) ||
                    (changes.occurredAtMillis != null && changes.occurredAtMillis != old.occurredAtMillis) ||
                    // "Don't count in totals" on one side would leave the other side netting alone
                    (changes.excludedFromTotals != null && changes.excludedFromTotals != old.excludedFromTotals)
            // the survivor of a broken pair keeps its type until it is unlinked: a type edit would be written back by the link rules
            val typeOfConverted = changes.type != null && changes.type != old.type && links.any { it.converted == old.id }
            if (typeOfConverted) return true
            if (!touchesMoney) return false
            return links.any { link ->
                val a = db.expenses().find(link.first)
                val b = db.expenses().find(link.second)
                a != null && b != null && a.deletedAtMillis == null && b.deletedAtMillis == null
            }
        }

        /** Deletes this entry. What the pair or refund link implies for the other rows is applied by [AnswerWrites.reconcile]. */
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
                            // one timestamp for the rows that go together: Undo restores them only if they went at the same moment
                            val now = clock.millis()
                            val first = db.expenses().softDelete(id, now)
                            if (first > 0) {
                                if (withPartner) {
                                    links
                                        .mapNotNull {
                                            it.deletablePartnerOf(
                                                id,
                                            )
                                        }.forEach { db.expenses().softDelete(it, now) }
                                }
                                AnswerWrites.reconcile(db, links)
                            }
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

        override suspend fun restore(id: String): Boolean = restoreOutcome(id) == RestoreOutcome.Restored

        override suspend fun restoreOutcome(id: String): RestoreOutcome =
            when {
                // its sources and receipts live on the survivor now: restoring it would count the purchase twice (STORY-173)
                pairs.isMergedAway(id) -> RestoreOutcome.MergedAway
                restoreRows(id) -> RestoreOutcome.Restored
                else -> RestoreOutcome.NothingToRestore
            }

        private suspend fun restoreRows(id: String): Boolean {
            val links = pairs.linksOf(id)
            val partners = links.mapNotNull { it.deletablePartnerOf(id) }
            val changed =
                try {
                    eraseLock.mutex.withLock {
                        db.withTransaction {
                            val now = clock.millis()
                            val deletedAt = db.expenses().find(id)?.deletedAtMillis
                            val first = db.expenses().restore(id, now)
                            if (first > 0) {
                                partners.filter { db.expenses().find(it)?.deletedAtMillis == deletedAt }.forEach {
                                    db.expenses().restore(it, now)
                                }
                                AnswerWrites.reconcile(db, links)
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
