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
    val restore: List<ExpenseEntity> = emptyList(),
    val deleteExpenses: List<String> = emptyList(),
    val deleteAccounts: List<String> = emptyList(),
    val added: AnswerState = AnswerState(),
    val atmBefore: AtmChoice? = null,
    /** The "Count ATM withdrawals as spending" value before this answer changed it; null = not touched. */
    val includeCashBefore: Boolean? = null,
) {
    // Never prints entries (merchants, amounts).
    override fun toString() = "AnswerUndo(restore=${restore.size}, inserted=${deleteExpenses.size})"
}

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

    /** An answer or a delete released a pair: [deletedId] was just soft-deleted; see [EntryLink] for what goes with it. */
    suspend fun release(
        db: AppDatabase,
        links: List<EntryLink>,
        deletedId: String,
        deleteWithPartner: Boolean,
        now: Long,
        deleteRefund: Boolean = deleteWithPartner,
    ) {
        for (link in links) {
            val partner = link.deletablePartnerOf(deletedId)
            when (link.kind) {
                LinkKind.TRANSFER -> {
                    if (deleteWithPartner && partner != null) db.expenses().softDelete(partner, now)
                    if (link.converted.isNotEmpty() && link.converted != deletedId) revertConverted(db, link, now)
                }
                LinkKind.REFUND -> if (partner != null) releaseRefund(db, partner, deleteRefund, now)
            }
        }
    }

    private suspend fun releaseRefund(
        db: AppDatabase,
        refundId: String,
        delete: Boolean,
        now: Long,
    ) {
        if (delete) {
            db.expenses().softDelete(refundId, now)
            return
        }
        // a refund without its purchase would net spending below zero: it is received money again
        val refund = db.expenses().find(refundId)?.takeIf { it.deletedAtMillis == null && it.type == ExpenseTypes.REFUND } ?: return
        db.expenses().update(refund.copy(type = ExpenseTypes.INCOME, updatedAtMillis = now))
    }

    private suspend fun revertConverted(
        db: AppDatabase,
        link: EntryLink,
        now: Long,
    ) {
        val row = db.expenses().find(link.converted)?.takeIf { it.deletedAtMillis == null } ?: return
        val converted = row.type == ExpenseTypes.TRANSFER || row.type == ExpenseTypes.TRANSFER_IN
        if (!converted || row.type == link.origType || link.origType.isEmpty()) return
        db.expenses().update(row.copy(type = link.origType, accountId = link.origAccountId.ifEmpty { null }, updatedAtMillis = now))
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
        db.expenses().update(entry.copy(type = ExpenseTypes.TRANSFER, updatedAtMillis = now))
        val inId = insertPairSide(db, entry, ExpenseTypes.TRANSFER_IN, cashId, null, now)
        return AnswerUndo(
            restore = listOf(entry),
            deleteExpenses = listOf(inId),
            deleteAccounts = listOfNotNull(cashId.takeIf { existing == null }),
            added =
                AnswerState(
                    answered = setOf(entry.id),
                    links =
                        setOf(
                            EntryLink(
                                LinkKind.TRANSFER,
                                entry.id,
                                inId,
                                inserted = inId,
                                converted = entry.id,
                                origType = entry.type,
                                origAccountId = entry.accountId.orEmpty(),
                            ),
                        ),
                ),
        )
    }
}
