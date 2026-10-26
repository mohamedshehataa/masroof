package eg.masroof.ingest.parse

/** Where a message came from. The wire value of the contract's `source` enum. */
enum class CaptureSource(
    val wire: String,
) {
    SMS("SMS"),
    NOTIFICATION("NOTIFICATION"),
}

/** What a bank or wallet message says happened. The wire value of the contract's `kind` enum. */
enum class TxKind {
    PURCHASE,
    TRANSFER_OUT,
    TRANSFER_IN,
    INCOME,
    REFUND,
    CASH_WITHDRAWAL,
    FEE,
}

/**
 * The only thing about a message that ever leaves the phone (REQ-SMS-001). There is no field for message text: the
 * single free-text value is [merchant], a short label with every digit removed.
 */
data class ParsedRecord(
    /** SHA-256 hex of sender, time and text, computed on the device; reveals nothing about the text. */
    val localId: String,
    val source: CaptureSource,
    val kind: TxKind,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    /** Exactly four digits, only from an explicit card/account marker. */
    val instrumentLast4: String?,
    val senderId: String,
    val institution: String?,
    val paymentMethod: String?,
    val merchant: String?,
)

/** A message as received: the sender id (SMS) or package name (notification) and the text (title + body). */
data class IncomingMessage(
    val source: CaptureSource,
    val sender: String,
    val text: String,
    val receivedAtMillis: Long,
) {
    /** Never prints the text. */
    override fun toString() = "IncomingMessage(source=$source, ts=$receivedAtMillis)"
}

/** What the engine decided about one message. */
sealed interface ParseOutcome {
    /** A known transaction template matched and the message is not secret: upload this record. */
    data class Record(
        val record: ParsedRecord,
    ) : ParseOutcome

    /**
     * Not understood, not a proven family, or looks secret: it stays on the phone, in the local "Needs review" list. [text] is
     * shown locally. [suggestion] is what the generic engine extracted (kind, amount, last4, label, date); the review screen
     * pre-fills a one-tap "Save" with it, and only that tap uploads it. It never leaves the phone by itself.
     */
    data class Review(
        val reason: String,
        val text: String,
        val suggestion: ParsedRecord? = null,
    ) : ParseOutcome {
        override fun toString() = "Review(reason=$reason)"
    }

    /** Dropped on the device and kept nowhere: OTP, promotion, declined payment, statement reminder. */
    data class Ignore(
        val reason: String,
    ) : ParseOutcome
}
