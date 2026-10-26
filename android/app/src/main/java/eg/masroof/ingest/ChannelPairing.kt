package eg.masroof.ingest

import androidx.room.withTransaction
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TxKind
import java.time.Clock

/** An upload that started longer ago than this is taken to have died; its row may be sent again by another uploader. */
const val UPLOAD_CLAIM_TTL_MILLIS = 5L * 60 * 1000

/** What to do with a record that may be the second announcement of a transfer. */
internal enum class Pairing { STORE, STORE_PAIRED, SKIP }

/** [twinId]: the queued record that a [Pairing.STORE_PAIRED] newcomer replaced (remembered, so its message is never taken twice). */
internal data class PairingMatch(
    val pairing: Pairing,
    val twinId: String? = null,
)

/**
 * One transfer announced by two channels (bank SMS + app notification) is one record: same direction, amount and currency, received
 * within [DUPLICATE_WINDOW_MILLIS] of each other. Each record pairs with at most one record of the other channel. Rules for who stays:
 * a queued record that an upload ever started is never deleted (the server may have it; a retry is idempotent on its local id);
 * otherwise the record that knows more (the account tail) stays.
 */
internal class ChannelPairing(
    private val db: AppDatabase,
    private val clock: Clock,
    private val notifier: CapturedNotifier,
) {
    /** A new message [r] (local id [id], received [receivedAt]) against what the other channel already recorded. */
    suspend fun matchIncoming(
        r: ParsedRecord,
        id: String,
        receivedAt: Long,
    ): PairingMatch {
        val kinds = directionKinds(r.kind) ?: return PairingMatch(Pairing.STORE)
        for (twin in twinsOf(r.source.name, r.currency, r.amountMinor, kinds, receivedAt)) {
            if (db.captured().claimPair(twin.localId, id) == 0) continue
            val richer = twin.instrumentLast4 == null && r.instrumentLast4 != null && id != twin.localId
            if (richer && db.parsedOutbox().deleteIfUnclaimed(twin.localId, staleBefore()) == 1) {
                db.captured().delete(twin.localId)
                notifier.cancel(twin.localId)
                return PairingMatch(Pairing.STORE_PAIRED, twin.localId)
            }
            return PairingMatch(Pairing.SKIP)
        }
        return PairingMatch(Pairing.STORE)
    }

    /**
     * Records that were both queued before they could be compared (made by an older version of the app) are paired the same way,
     * before an upload pass sends them.
     */
    suspend fun pairQueued() {
        for (a in db.captured().queuedUnpaired()) db.withTransaction { pairQueuedOne(a.localId) }
    }

    private suspend fun pairQueuedOne(id: String) {
        val a = db.captured().find(id)?.takeIf { !it.paired } ?: return
        val aOut = db.parsedOutbox().find(id) ?: return
        val kinds = a.kind?.let { runCatching { TxKind.valueOf(it) }.getOrNull() }?.let(::directionKinds) ?: return
        for (twin in twinsOf(a.source, a.currency, a.amountMinor, kinds, a.receivedAtMillis)) {
            if (db.captured().claimPair(twin.localId, a.localId) == 0) continue
            db.captured().claimPair(a.localId, twin.localId)
            val loser = loserOf(a, aOut, twin, db.parsedOutbox().find(twin.localId))
            if (loser != null && db.parsedOutbox().deleteIfUnclaimed(loser, staleBefore()) == 1) {
                db.captured().delete(loser)
                notifier.cancel(loser)
            }
            return
        }
    }

    /** The local id to drop, or null when both must stay (each was ever claimed: either may be on the server). */
    private fun loserOf(
        a: CapturedTxEntity,
        aOut: ParsedOutboxEntity,
        twin: CapturedTxEntity,
        twinOut: ParsedOutboxEntity?,
    ): String? =
        when {
            twinOut == null -> a.localId.takeIf { aOut.claims == 0 } // the twin is already on the server: a is the duplicate
            aOut.claims > 0 && twinOut.claims > 0 -> null
            aOut.claims > 0 -> twin.localId
            twinOut.claims > 0 -> a.localId
            a.instrumentLast4 == null && twin.instrumentLast4 != null -> a.localId
            twin.instrumentLast4 == null && a.instrumentLast4 != null -> twin.localId
            else -> if (a.receivedAtMillis >= twin.receivedAtMillis) a.localId else twin.localId
        }

    private suspend fun twinsOf(
        source: String,
        currency: String,
        amountMinor: Long,
        kinds: List<String>,
        receivedAt: Long,
    ): List<CapturedTxEntity> =
        db.captured().otherChannel(
            source,
            currency,
            amountMinor,
            kinds,
            receivedAt,
            receivedAt - DUPLICATE_WINDOW_MILLIS,
            receivedAt + DUPLICATE_WINDOW_MILLIS,
        )

    private fun staleBefore() = clock.millis() - UPLOAD_CLAIM_TTL_MILLIS

    private fun directionKinds(kind: TxKind): List<String>? =
        when (kind) {
            TxKind.INCOME, TxKind.TRANSFER_IN -> INCOMING_KINDS.toList()
            TxKind.TRANSFER_OUT -> listOf(TxKind.TRANSFER_OUT.name)
            else -> null
        }

    private companion object {
        const val DUPLICATE_WINDOW_MILLIS = 5L * 60 * 1000
    }
}
