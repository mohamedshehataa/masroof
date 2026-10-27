package eg.masroof.data

import androidx.room.withTransaction
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.OutboxEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * REQ-AND-133: a manual entry that never uploaded (the old `outbox`, failed ones too) becomes a local expense with a `MANUAL`
 * source whose `localSourceId` is the old client id. Each row is converted in its own transaction, so an interrupted run leaves
 * every row either fully converted or fully untouched, and a second run finishes the rest without duplicating anything.
 * Nothing is uploaded and amounts or notes are never logged.
 */
class OutboxConverter(
    private val db: AppDatabase,
    private val clock: Clock,
    private val ids: IdFactory,
) {
    private val lock = Mutex()

    suspend fun convertAll(): Int =
        lock.withLock {
            var converted = 0
            for (row in db.outbox().all().sortedBy { it.createdAtMillis }) {
                if (convert(row)) converted++
            }
            converted
        }

    private suspend fun convert(row: OutboxEntity): Boolean =
        db.withTransaction {
            if (db.sources().countByLocalSourceId(row.clientId) > 0) {
                // Already converted (a source row exists): only the queue entry is left over.
                db.outbox().delete(row.clientId)
                return@withTransaction false
            }
            val id = ids.next()
            val now = clock.millis()
            val valid = row.amountMinor > 0 && CURRENCY.matches(row.currency)
            db.expenses().insert(
                ExpenseEntity(
                    id = id,
                    amountMinor = row.amountMinor,
                    currency = row.currency,
                    occurredAtMillis = row.occurredAtMillis,
                    merchant = null,
                    categoryId = row.categoryId,
                    accountId = row.accountId,
                    notes = row.notes,
                    pending = false,
                    failed = false,
                    source = CHANNEL_MANUAL,
                    // A corrupt row is kept as it is, for the user to fix or delete, instead of being dropped.
                    status = if (valid) "CONFIRMED" else "PENDING_REVIEW",
                    createdAtMillis = row.createdAtMillis,
                    updatedAtMillis = now,
                    timeKnown = false,
                ),
            )
            db.sources().insert(
                ExpenseSourceEntity(
                    id = ids.next(),
                    expenseId = id,
                    channel = CHANNEL_MANUAL,
                    localSourceId = row.clientId,
                    receivedAtMillis = row.createdAtMillis,
                    senderId = null,
                    instrumentLast4 = null,
                ),
            )
            // The old pending cache row goes; what hangs on it moves to the new row first.
            db.expenses().moveSources(row.clientId, id)
            db.expenses().moveReceipts(row.clientId, id)
            db.expenses().delete(row.clientId)
            db.outbox().delete(row.clientId)
            true
        }

    private companion object {
        val CURRENCY = Regex("[A-Z]{3}")
    }
}
