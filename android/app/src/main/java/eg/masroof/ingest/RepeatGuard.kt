package eg.masroof.ingest

import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TxKind

/** `review_item.reason` of a backfilled message that may be a message live capture already handled (STORY-186). */
const val REASON_POSSIBLE_REPEAT = "possible_repeat"

/** How far apart two channels' copies of one transfer may be (the live pairing window). */
internal const val TWIN_WINDOW_MILLIS = 5L * 60 * 1000

/**
 * What a backfilled message is compared with before it may become an entry, beyond its own id: what live capture made of the same
 * message or transfer (STORY-186). Every key it keeps (through [HandledMemory]) is a keyed HMAC of typed values or of the message
 * text, with the time: nothing readable, nothing that leaves the phone, pruned after 30 days.
 */
internal class RepeatGuard(
    private val db: AppDatabase,
    private val memory: HandledMemory,
    private val key: () -> ByteArray,
) {
    /** The keys a captured message adds to the memory after it was handled, with the time each one is looked up around. */
    fun marksOf(
        r: ParsedRecord,
        receivedAt: Long,
    ): List<Pair<String, Long>> =
        listOfNotNull(
            smsSignature(r).takeIf { r.source == CaptureSource.SMS }?.let { it to receivedAt },
            pushSignature(r)?.let { it to receivedAt },
        )

    fun reviewMark(
        sender: String,
        text: String,
    ): String = LocalIdHasher.signature(key(), "review", senderKey(sender), text)

    private fun smsSignature(r: ParsedRecord) =
        LocalIdHasher.signature(key(), "sms", senderKey(r.senderId), r.amountMinor, r.currency, r.instrumentLast4)

    /** A notification's transfer without who sent it (the SMS twin comes from another sender): direction, amount, currency. */
    private fun pushSignature(r: ParsedRecord): String? =
        if (r.source == CaptureSource.NOTIFICATION && directional(r.kind)) {
            LocalIdHasher.signature(key(), "push", CaptureRows.typeOf(r.kind), r.amountMinor, r.currency)
        } else {
            null
        }

    private fun directional(kind: TxKind) = kind == TxKind.INCOME || kind == TxKind.TRANSFER_IN || kind == TxKind.TRANSFER_OUT

    /**
     * A message without a usable sent time (its time may be seconds off the live one's): the same sender, amount and card tail was
     * captured live within [slack], as an entry (deleted ones too) or, once purged, by the memory.
     */
    suspend fun isPossibleRepeat(
        r: ParsedRecord,
        receivedAt: Long,
        slack: Long,
    ): Boolean =
        db.sources().liveSmsCount(r.senderId, r.currency, r.amountMinor, r.instrumentLast4, receivedAt - slack, receivedAt + slack) > 0 ||
            memory.seenSignature(smsSignature(r), receivedAt - slack, receivedAt + slack)

    /** The same unrecognised message already waits in the review list, or was there and was dismissed (memory). */
    suspend fun isReviewRepeat(
        sender: String,
        text: String,
        receivedAt: Long,
        slack: Long,
    ): Boolean =
        db.reviews().countSimilar(sender, text, receivedAt - slack, receivedAt + slack) > 0 ||
            memory.seenSignature(reviewMark(sender, text), receivedAt - slack, receivedAt + slack)

    /** The notification entry (deleted ones too) that this SMS announces again, whose "Recorded" row has expired. */
    suspend fun pushTwin(
        r: ParsedRecord,
        receivedAt: Long,
    ): ExpenseEntity? =
        if (directional(r.kind)) {
            db.sources().pushTwinOf(
                CaptureRows.typeOf(r.kind),
                r.currency,
                r.amountMinor,
                receivedAt - TWIN_WINDOW_MILLIS,
                receivedAt + TWIN_WINDOW_MILLIS,
                receivedAt,
            )
        } else {
            null
        }

    /** The notification entry is gone for good (purged from the trash): only the memory knows it existed. */
    suspend fun isPurgedPushTwin(
        r: ParsedRecord,
        receivedAt: Long,
    ): Boolean {
        if (!directional(r.kind)) return false
        val from = receivedAt - TWIN_WINDOW_MILLIS
        val to = receivedAt + TWIN_WINDOW_MILLIS
        // a notification entry that still exists (already paired with its own SMS) is what the memory refers to: not purged
        if (db.sources().pushSourceCount(CaptureRows.typeOf(r.kind), r.currency, r.amountMinor, from, to) > 0) return false
        return pushSignature(r.copy(source = CaptureSource.NOTIFICATION))?.let { memory.seenSignature(it, from, to) } == true
    }
}
