package eg.masroof.ingest

import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TxKind
import java.time.Clock

/** `review_item.reason` of a backfilled message that may be a message live capture already handled (STORY-186). */
const val REASON_POSSIBLE_REPEAT = "possible_repeat"

/** How far apart two channels' copies of one transfer may be (the live pairing window). */
internal const val TWIN_WINDOW_MILLIS = 5L * 60 * 1000

/** How far the provider's time of one SMS may be from the live PDU's when the provider has no sent time. */
internal const val SAME_SMS_SLACK_MILLIS = 2L * 60 * 1000

/**
 * What a backfilled (or a late live) message is compared with before it may become an entry, beyond its own id: what the other
 * capture made of the same message or transfer (STORY-186). The rule is the same everywhere: when the app cannot tell, the message
 * goes to review, never silently dropped and never counted twice. Every key it keeps (through [HandledMemory]) is a keyed HMAC of
 * typed values or of the message text, with the time sealed: nothing readable, pruned after 30 days.
 */
internal class RepeatGuard(
    private val db: AppDatabase,
    private val memory: HandledMemory,
    private val clock: Clock,
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

    /** The same keys for an entry that is about to be purged from the trash, from its stored facts (the sources outlive no purge). */
    fun marksOfPurged(row: PurgedSource): List<Pair<String, Long>> =
        listOfNotNull(
            row.takeIf { it.channel == CHANNEL_PUSH && directionOf(it.type) != null }?.let {
                LocalIdHasher.signature(key(), "push", it.type, it.amountMinor, it.currency) to it.receivedAtMillis
            },
            row.takeIf { it.channel == CHANNEL_SMS || it.channel == CHANNEL_BACKFILL }?.let {
                LocalIdHasher.signature(key(), "sms", senderKey(it.senderId.orEmpty()), it.amountMinor, it.currency, it.instrumentLast4) to
                    it.receivedAtMillis
            },
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

    private fun directionOf(type: String) = type.takeIf { it == "INCOME" || it == "TRANSFER" }

    /**
     * A message without a usable sent time (its time may be seconds off the live one's): the same sender, amount and card tail was
     * captured live within [slack], as an entry (deleted ones too) or, once purged, by the memory.
     */
    suspend fun isPossibleRepeat(
        r: ParsedRecord,
        receivedAt: Long,
        slack: Long,
    ): Boolean =
        db.sources().smsCount(
            CHANNEL_SMS,
            r.senderId,
            r.currency,
            r.amountMinor,
            r.instrumentLast4,
            receivedAt - slack,
            receivedAt + slack,
        ) >
            0 ||
            memory.seenSignature(smsSignature(r), receivedAt - slack, receivedAt + slack, clock.millis())

    /** A live message that a backfill already recorded from a row without a sent time (the backfill read it first). */
    suspend fun isBackfilledSibling(
        r: ParsedRecord,
        receivedAt: Long,
    ): Boolean =
        r.source == CaptureSource.SMS &&
            db
                .sources()
                .smsCount(
                    CHANNEL_BACKFILL,
                    r.senderId,
                    r.currency,
                    r.amountMinor,
                    r.instrumentLast4,
                    receivedAt - SAME_SMS_SLACK_MILLIS,
                    receivedAt + SAME_SMS_SLACK_MILLIS,
                ) > 0

    /** The same unrecognised message already waits in the review list, or was there and was dismissed (memory). */
    suspend fun isReviewRepeat(
        sender: String,
        text: String,
        receivedAt: Long,
        slack: Long,
    ): Boolean =
        db.reviews().countSimilar(sender, text, receivedAt - slack, receivedAt + slack) > 0 ||
            memory.seenSignature(reviewMark(sender, text), receivedAt - slack, receivedAt + slack, clock.millis())

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

    /**
     * The notification entry is gone for good (purged from the trash): only the memory knows it existed. The closest SMS claims it
     * once ([SignatureClaim.CLAIMED]: it is that entry's twin); a further one cannot be told from a second real transfer
     * ([SignatureClaim.ALREADY_CLAIMED]: review).
     */
    suspend fun claimPurgedPush(
        r: ParsedRecord,
        receivedAt: Long,
    ): SignatureClaim {
        if (!directional(r.kind)) return SignatureClaim.NONE
        val from = receivedAt - TWIN_WINDOW_MILLIS
        val to = receivedAt + TWIN_WINDOW_MILLIS
        // a notification entry that still exists (already paired with its own SMS) is what the memory refers to: not purged
        if (db.sources().pushSourceCount(CaptureRows.typeOf(r.kind), r.currency, r.amountMinor, from, to) > 0) return SignatureClaim.NONE
        val signature = pushSignature(r.copy(source = CaptureSource.NOTIFICATION)) ?: return SignatureClaim.NONE
        return memory.claimSignature(signature, from, to, receivedAt, clock.millis())
    }

    /**
     * A notification entry of this direction and currency close in time whose amount no longer matches because the user edited it (the
     * original amount is not kept): it may be this transfer, so the SMS cannot be told apart and goes to review.
     */
    suspend fun isEditedPushNear(
        r: ParsedRecord,
        receivedAt: Long,
    ): Boolean =
        directional(r.kind) &&
            db.sources().editedPushNearCount(
                CaptureRows.typeOf(r.kind),
                r.currency,
                receivedAt - TWIN_WINDOW_MILLIS,
                receivedAt + TWIN_WINDOW_MILLIS,
            ) > 0
}

/** What the trash purge knows about one source of an entry it is about to remove for good. */
data class PurgedSource(
    val type: String,
    val currency: String,
    val amountMinor: Long,
    val channel: String,
    val senderId: String?,
    val instrumentLast4: String?,
    val receivedAtMillis: Long,
)
