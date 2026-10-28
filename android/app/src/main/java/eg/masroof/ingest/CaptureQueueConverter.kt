package eg.masroof.ingest

import androidx.room.withTransaction
import eg.masroof.core.CairoTime
import eg.masroof.core.MoneyFormat
import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TxKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * REQ-SMS-099 (ADR-0009 D3 "Migrate"): a capture an older version left in `parsed_outbox` (unsent, failed, or claimed by an upload
 * whose answer may have been lost) becomes one local expense with its `expense_source`. Every row is converted in its own
 * transaction (expense, source, `captured_tx` repoint and outbox delete together), idempotent by the row's local id, so an
 * interrupted run resumes without duplicates. Two rows of one transfer announced by both channels become one entry with two
 * sources. Nothing is uploaded, `serverId` stays null, and no text exists to log (typed fields only).
 */
internal class CaptureQueueConverter(
    private val db: AppDatabase,
    private val clock: Clock,
    private val ids: IdFactory,
    private val eraseLock: EraseLock = EraseLock(),
) {
    private val lock = Mutex()
    private val pairing = ChannelPairing(db, clock, NoCapturedNotifier)

    /** Returns how many rows became entries or joined one. */
    suspend fun convertAll(): Int =
        lock.withLock {
            var converted = 0
            // Waits for a running erase, like every other write of an entry.
            eraseLock.mutex.withLock {
                for (row in db.parsedOutbox().all().sortedWith(
                    compareBy({ it.occurredAtMillis }, { it.createdAtMillis }, { it.localId }),
                )) {
                    if (db.withTransaction { convert(row) }) converted++
                }
            }
            converted
        }

    private suspend fun convert(row: ParsedOutboxEntity): Boolean {
        if (db.sources().countByLocalSourceId(row.localId) > 0) {
            // converted before the process died: only the queue row is left over
            db.parsedOutbox().delete(row.localId)
            return false
        }
        val now = clock.millis()
        val kind = runCatching { TxKind.valueOf(row.kind) }.getOrNull()
        val record = recordOf(row, kind ?: TxKind.PURCHASE)
        val captured = db.captured().find(row.localId)
        val receivedAt = captured?.receivedAtMillis?.takeIf { it > 0 } ?: row.createdAtMillis
        // rows the old pairQueued already matched point at each other: the second to convert joins the first one's entry
        val partner = captured?.takeIf { it.paired }?.pairedLocalId
        val partnerExpense = partner?.let { db.sources().expenseIdOfLocalSource(it) }?.let { db.expenses().find(it) }
        val partnerRow = partner?.let { db.captured().find(it) }
        val twin =
            when {
                partnerExpense != null && partnerRow != null -> LocalTwin(partnerRow, partnerExpense)
                captured?.paired == true || kind == null -> null
                else -> pairing.claimTwin(record, row.localId, receivedAt)
            }
        if (twin != null) {
            db.sources().insert(CaptureRows.source(ids.next(), twin.expense.id, record, row.localId, receivedAt))
            if (twin.captured.instrumentLast4 == null && record.instrumentLast4 != null) {
                db.expenses().enrichFromTwin(twin.expense.id, record.instrumentLast4, CaptureRows.label(record))
            }
            if (captured != null) {
                db.captured().setExpenseId(row.localId, twin.expense.id)
                db.captured().claimPair(row.localId, twin.captured.localId)
            }
        } else {
            val expenseId = ids.next()
            val entry = CaptureRows.expense(expenseId, record, now)
            // a row nobody can trust (corrupt kind, amount or time) is kept for the user to check, never dropped
            db.expenses().insert(if (suspicious(row, kind, now)) entry.copy(status = "PENDING_REVIEW") else entry)
            db.sources().insert(CaptureRows.source(ids.next(), expenseId, record, row.localId, receivedAt))
            if (captured != null) db.captured().setExpenseId(row.localId, expenseId)
        }
        db.parsedOutbox().delete(row.localId)
        return true
    }

    private fun suspicious(
        row: ParsedOutboxEntity,
        kind: TxKind?,
        now: Long,
    ) = kind == null ||
        row.amountMinor !in 1..MoneyFormat.MAX_ENTRY_MINOR ||
        row.occurredAtMillis < now - MAX_AGE_MILLIS ||
        row.occurredAtMillis > now + MAX_AHEAD_MILLIS

    private companion object {
        const val MAX_AGE_MILLIS = 45L * 24 * 60 * 60 * 1000
        const val MAX_AHEAD_MILLIS = 24L * 60 * 60 * 1000
    }

    private fun recordOf(
        row: ParsedOutboxEntity,
        kind: TxKind,
    ) = ParsedRecord(
        localId = row.localId,
        source = runCatching { CaptureSource.valueOf(row.source) }.getOrDefault(CaptureSource.SMS),
        kind = kind,
        amountMinor = row.amountMinor,
        currency = row.currency,
        occurredAtMillis = row.occurredAtMillis,
        instrumentLast4 = row.instrumentLast4,
        senderId = row.senderId,
        institution = row.institution,
        paymentMethod = row.paymentMethod,
        merchant = row.merchant,
        // The queue never stored it: Cairo noon exactly is how a date-only message was written (schema-free).
        timeKnown = row.occurredAtMillis != CairoTime.noonOf(CairoTime.dateOf(row.occurredAtMillis)).toEpochMilli(),
    )
}
