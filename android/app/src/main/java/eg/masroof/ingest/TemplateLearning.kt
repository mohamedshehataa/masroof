package eg.masroof.ingest

import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.LearnedTemplateEntity
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ingest.parse.MessageParser
import eg.masroof.ingest.parse.ParseOutcome
import eg.masroof.ingest.parse.TemplateShape
import eg.masroof.ingest.parse.TxKind
import java.security.MessageDigest

/**
 * On-device template learning (round 15). When the user saves a review item, the digit-free shape of its message is stored with
 * the sender and the confirmed kind. Later messages of that sender with exactly that shape are recorded without asking. If the
 * user corrected the amount (or last four) the number slot that held the right value is remembered, so the template reads that
 * slot next time. Nothing here leaves the phone.
 */
object TemplateLearning {
    private const val MAX_SENDER = 60
    private const val MAX_AMOUNT_MINOR = 100_000_000_000L
    private const val NO_SLOT = -1
    private const val LAST4_LENGTH = 4
    const val SLOT_SEPARATOR = "|"

    fun templateId(
        sender: String,
        shape: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$sender\n$shape".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The slots of a review item as stored: the raw number tokens, in order. */
    fun encodeSlots(numbers: List<String>): String = numbers.joinToString(SLOT_SEPARATOR)

    private fun decodeSlots(slots: String?): List<String> = slots?.split(SLOT_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    /** A review outcome that matches a template the user confirmed becomes a record; anything else is returned unchanged. */
    suspend fun apply(
        db: AppDatabase,
        message: IncomingMessage,
        outcome: ParseOutcome,
    ): ParseOutcome {
        val review = outcome as? ParseOutcome.Review ?: return outcome
        val suggestion = review.suggestion ?: return outcome
        val shape = TemplateShape.of(message.text) ?: return outcome
        val template = db.learnedTemplates().find(message.sender.take(MAX_SENDER), shape.signature) ?: return outcome
        var record = suggestion.copy(kind = TxKind.valueOf(template.kind))
        shape.numbers.getOrNull(template.amountSlot)?.let { raw ->
            MessageParser.minorOf(raw)?.takeIf { it in 1..MAX_AMOUNT_MINOR }?.let { record = record.copy(amountMinor = it) }
        }
        shape.numbers.getOrNull(template.last4Slot)?.takeIf { it.length == LAST4_LENGTH && it.all(Char::isDigit) }?.let {
            record = record.copy(instrumentLast4 = it)
        }
        return ParseOutcome.Record(record)
    }

    /**
     * Remembers the format of [item] as confirmed. [amountMinor] / [last4] are the values the user saved when they changed them:
     * the slot that held them is stored, if any slot did. A secret-looking message has no shape and is never learned.
     */
    suspend fun learn(
        db: AppDatabase,
        item: ReviewItemEntity,
        now: Long,
        amountMinor: Long? = null,
        last4: String? = null,
    ) {
        val shape = item.shape ?: return
        val kind = item.sugKind ?: return
        val slots = decodeSlots(item.slots)
        val amountSlot =
            if (amountMinor != null && amountMinor != item.sugAmountMinor) {
                slots.indexOfFirst { MessageParser.minorOf(it) == amountMinor }
            } else {
                NO_SLOT
            }
        val last4Slot = if (last4 != null && last4 != item.sugLast4) slots.indexOfFirst { it == last4 } else NO_SLOT
        val sender = item.sender.take(MAX_SENDER)
        db.learnedTemplates().upsert(
            LearnedTemplateEntity(
                id = templateId(sender, shape),
                sender = sender,
                shape = shape,
                kind = kind,
                amountSlot = amountSlot,
                last4Slot = last4Slot,
                createdAtMillis = now,
            ),
        )
    }
}
