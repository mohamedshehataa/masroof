package eg.masroof.data.answers

import eg.masroof.data.CHANNEL_MANUAL
import eg.masroof.data.IdFactory
import eg.masroof.data.db.AccountBalanceEntity
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity

private const val CASH_KIND = "CASH"

/**
 * What one answer changed, so Undo can put back exactly what was there before. Row snapshots are the entries as they were;
 * inserted entries and accounts are removed again; [added] is what was added to the remembered state (and [atmBefore] the
 * choice it replaced).
 */
data class AnswerUndo(
    val restore: List<RowChange> = emptyList(),
    val deleteExpenses: List<String> = emptyList(),
    val deleteAccounts: List<String> = emptyList(),
    val added: AnswerState = AnswerState(),
    val atmBefore: AtmChoice? = null,
    /** The "Count ATM withdrawals as spending" value before this answer changed it; null = not touched. */
    val includeCashBefore: Boolean? = null,
    /** Older links of the entries this answer is about (a pair whose other side was deleted): the new link replaces them. */
    val replacedLinks: Set<EntryLink> = emptySet(),
    /** Rows of the replaced (or unlinked) links that were fixed in their original type, and inserted rows that were deleted. */
    val settledRows: List<RowChange> = emptyList(),
    val settledInserted: List<String> = emptyList(),
) {
    // Never prints entries (merchants, amounts).
    override fun toString() = "AnswerUndo(restore=${restore.size}, inserted=${deleteExpenses.size})"
}

/** One row an answer changed: how it was ([before]) and what the answer made of it ([after]). */
data class RowChange(
    val before: ExpenseEntity,
    val after: ExpenseEntity,
)

/** The row writes shared by the answers and by the capture pipeline (a remembered "Track it"). Always called inside a transaction. */
internal object AnswerWrites {
    /** A new entry on [accountId]: a TRANSFER_IN or a TRANSFER out that completes a pair. Manual channel, so it asks nothing. */
    @Suppress("LongParameterList") // typed fields of one row
    suspend fun insertPairSide(
        db: AppDatabase,
        like: ExpenseEntity,
        type: String,
        accountId: String,
        merchant: String?,
        now: Long,
    ): String {
        // Deterministic: an answer repeated after a crash between the Room commit and the remembered state finds its row again
        // instead of inserting a second one.
        val id = pairSideId(like.id)
        val row =
            ExpenseEntity(
                id = id,
                amountMinor = like.amountMinor,
                currency = like.currency,
                occurredAtMillis = like.occurredAtMillis,
                merchant = merchant,
                categoryId = null,
                accountId = accountId,
                notes = null,
                pending = false,
                source = CHANNEL_MANUAL,
                type = type,
                // a pending (over-ceiling or incoming) entry never gets a confirmed twin
                status = like.status,
                createdAtMillis = now,
                updatedAtMillis = now,
                timeKnown = like.timeKnown,
                paymentMethod = like.paymentMethod,
            )
        if (db.expenses().find(id) == null) {
            db.expenses().insert(row)
            db.sources().insert(
                ExpenseSourceEntity(
                    id = "$id:src",
                    expenseId = id,
                    channel = CHANNEL_MANUAL,
                    localSourceId = id,
                    receivedAtMillis = now,
                    senderId = null,
                    instrumentLast4 = null,
                ),
            )
        } else {
            db.expenses().update(row.copy(createdAtMillis = db.expenses().find(id)!!.createdAtMillis, deletedAtMillis = null))
        }
        return id
    }

    fun pairSideId(entryId: String) = "$entryId:pair"

    /**
     * The pair and refund fields as a pure function of the stored links and which rows are live. Called in the same Room transaction
     * after EVERY write that touches a linked row (answer, delete, restore, edit, capture Undo), so no order of operations can leave
     * them out of step:
     *  - a transfer pair whose two sides are live: the converted entry is in its paired type and account; if the other side is
     *    deleted it is back to its original type, account (the record keeps both);
     *  - an app-inserted row lives only while its source entry is live (it is deleted with the same timestamp, so a restore finds it);
     *  - a refund whose purchase is live is a REFUND in the purchase's CURRENT category; while the purchase is deleted it is INCOME.
     */
    suspend fun reconcile(
        db: AppDatabase,
        links: Collection<EntryLink>,
    ) {
        for (link in links) {
            when (link.kind) {
                LinkKind.TRANSFER -> reconcileTransfer(db, link)
                LinkKind.REFUND -> reconcileRefund(db, link)
            }
        }
    }

    private suspend fun reconcileTransfer(
        db: AppDatabase,
        link: EntryLink,
    ) {
        var a = db.expenses().find(link.first) ?: return
        var b = db.expenses().find(link.second) ?: return
        if (link.inserted.isNotEmpty()) {
            reconcileInserted(db, if (link.inserted == a.id) a else b, if (link.inserted == a.id) b else a)
            a = db.expenses().find(link.first) ?: return
            b = db.expenses().find(link.second) ?: return
        }
        if (link.converted.isEmpty() || link.pairType.isEmpty()) return
        val (c, other) = if (link.converted == a.id) a to b else b to a
        if (c.deletedAtMillis != null) return
        // whole = both sides live and the same money (an edit made while one side was deleted cannot rebuild a lopsided pair)
        val whole = other.deletedAtMillis == null && c.amountMinor == other.amountMinor && c.currency == other.currency
        val type = if (whole) link.pairType else link.origType
        val account = (if (whole) link.pairAccountId else link.origAccountId).ifEmpty { null }
        if (c.type != type || c.accountId != account) db.expenses().update(c.copy(type = type, accountId = account))
    }

    /** The app-inserted side follows its source: deleted with it (same timestamp, so a restore finds it) and the same money. */
    private suspend fun reconcileInserted(
        db: AppDatabase,
        inserted: ExpenseEntity,
        source: ExpenseEntity,
    ) {
        if (inserted.deletedAtMillis != null) return
        val sourceGone = source.deletedAtMillis
        when {
            sourceGone != null -> db.expenses().softDelete(inserted.id, sourceGone)
            !sameMoney(inserted, source) ->
                // safety net: the editor refuses money edits of linked rows, so this only repairs what could not be refused
                db.expenses().update(
                    inserted.copy(
                        amountMinor = source.amountMinor,
                        currency = source.currency,
                        occurredAtMillis = source.occurredAtMillis,
                        timeKnown = source.timeKnown,
                    ),
                )
        }
    }

    private fun sameMoney(
        x: ExpenseEntity,
        y: ExpenseEntity,
    ) = x.amountMinor == y.amountMinor && x.currency == y.currency && x.occurredAtMillis == y.occurredAtMillis

    private suspend fun reconcileRefund(
        db: AppDatabase,
        link: EntryLink,
    ) {
        val refund = db.expenses().find(link.first)?.takeIf { it.deletedAtMillis == null } ?: return
        val purchase = db.expenses().find(link.second) ?: return
        val whole = purchase.deletedAtMillis == null && refund.currency == purchase.currency && refund.amountMinor <= purchase.amountMinor
        val type = if (whole) ExpenseTypes.REFUND else ExpenseTypes.INCOME
        val category = if (whole) purchase.categoryId ?: refund.categoryId else refund.categoryId
        if (refund.type != type || refund.categoryId != category) {
            db.expenses().update(refund.copy(type = type, categoryId = category))
        }
    }

    /** Undo of the answer that made [link] (or Unlink): the converted entry goes back to what it was; edits of the rest are kept. */
    suspend fun unlink(
        db: AppDatabase,
        link: EntryLink,
    ) {
        if (link.converted.isEmpty()) return
        // live or deleted: a deleted entry that comes back later must come back as it was, not as half of a pair
        val row = db.expenses().find(link.converted) ?: return
        db.expenses().update(originalOf(link, row))
    }

    private fun originalOf(
        link: EntryLink,
        row: ExpenseEntity,
    ): ExpenseEntity {
        val refund = link.kind == LinkKind.REFUND
        return row.copy(
            type = link.origType.ifEmpty { row.type },
            accountId = if (refund) row.accountId else link.origAccountId.ifEmpty { null },
            status = link.origStatus.ifEmpty { row.status },
            categoryId = if (refund) link.origCategoryId.ifEmpty { null } else row.categoryId,
        )
    }

    /** What settling links did: the converted rows (before/after) and the app-inserted rows it soft-deleted. */
    data class Settled(
        val rows: List<RowChange>,
        val insertedDeleted: List<String>,
    )

    /**
     * Makes [links] final: the app-inserted row is deleted, the converted real entry (live or deleted) is fixed in its ORIGINAL
     * type. A later restore of an old deleted side then brings back a plain entry, never a half of a pair that no longer exists.
     * Used when a new answer replaces a link that lost a side, and by Unlink.
     */
    suspend fun settle(
        db: AppDatabase,
        links: Collection<EntryLink>,
        now: Long,
    ): Settled {
        val rows = mutableListOf<RowChange>()
        val deleted = mutableListOf<String>()
        for (link in links) {
            if (link.inserted.isNotEmpty()) {
                val inserted = db.expenses().find(link.inserted)
                if (inserted != null && inserted.deletedAtMillis == null) {
                    db.expenses().softDelete(link.inserted, now)
                    deleted += link.inserted
                }
            }
            val converted = if (link.converted.isEmpty()) null else db.expenses().find(link.converted)
            if (converted != null) {
                val original = originalOf(link, converted)
                if (original != converted) rows += change(db, converted, original)
            }
        }
        return Settled(rows, deleted)
    }

    /** Writes [after] over [before] and remembers both, so Undo can revert exactly the fields this change touched. */
    suspend fun change(
        db: AppDatabase,
        before: ExpenseEntity,
        after: ExpenseEntity,
    ): RowChange {
        db.expenses().update(after)
        return RowChange(before, after)
    }

    /**
     * Puts a [RowChange] back (answers that have no link: confirm, Not a refund, a linked card). A row nobody touched since goes back
     * exactly as it was; one the user edited meanwhile gets only the fields this change set reverted, and keeps the edit.
     */
    suspend fun revert(
        db: AppDatabase,
        c: RowChange,
    ) {
        val cur = db.expenses().find(c.before.id) ?: return
        if (cur == c.after) {
            db.expenses().update(c.before)
            return
        }
        db.expenses().update(
            cur.copy(
                type = if (cur.type == c.after.type) c.before.type else cur.type,
                categoryId = if (cur.categoryId == c.after.categoryId) c.before.categoryId else cur.categoryId,
                accountId = if (cur.accountId == c.after.accountId) c.before.accountId else cur.accountId,
                status = if (cur.status == c.after.status) c.before.status else cur.status,
            ),
        )
    }

    /**
     * "Track it": the withdrawal becomes a TRANSFER out and a TRANSFER_IN lands in a Cash account, which is created when the
     * phone has none (with a zero balance check just before the withdrawal, so it shows a balance).
     */
    suspend fun trackWithdrawal(
        db: AppDatabase,
        ids: IdFactory,
        entry: ExpenseEntity,
        accounts: List<AccountEntity>,
        cashLabel: String,
        now: Long,
    ): AnswerUndo {
        val existing = accounts.firstOrNull { !it.archived && it.kind == CASH_KIND && it.currency == entry.currency }
        val cashId = existing?.id ?: ids.next()
        if (existing == null) {
            db.lookups().upsertAccounts(listOf(AccountEntity(id = cashId, label = cashLabel, currency = entry.currency, kind = CASH_KIND)))
            db.accountBalances().insert(
                AccountBalanceEntity(ids.next(), cashId, 0L, entry.currency, minOf(entry.occurredAtMillis - 1, now), "MANUAL", now),
            )
        }
        db.expenses().update(entry.copy(type = ExpenseTypes.TRANSFER))
        val inId = insertPairSide(db, entry, ExpenseTypes.TRANSFER_IN, cashId, null, now)
        val link =
            EntryLink(
                LinkKind.TRANSFER,
                entry.id,
                inId,
                inserted = inId,
                converted = entry.id,
                origType = entry.type,
                origAccountId = entry.accountId.orEmpty(),
                pairType = ExpenseTypes.TRANSFER,
                pairAccountId = entry.accountId.orEmpty(),
                origStatus = entry.status,
                newAccountId = cashId.takeIf { existing == null }.orEmpty(),
            )
        return AnswerUndo(
            deleteExpenses = listOf(inId),
            deleteAccounts = listOfNotNull(cashId.takeIf { existing == null }),
            added = AnswerState(answered = setOf(entry.id), links = setOf(link)),
        )
    }
}
