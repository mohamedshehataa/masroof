package eg.masroof.ingest

import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.ParsedRecord
import eg.masroof.ingest.parse.TxKind

internal const val CHANNEL_SMS = "SMS"
internal const val CHANNEL_PUSH = "PUSH"

/** The local rows a parsed record becomes (ADR-0009 D3). Shared by the capture pipeline and the review list's Save. */
internal object CaptureRows {
    private const val REVIEW_CEILING_MINOR = 5_000_000L
    private const val BASE_CURRENCY = "EGP"

    /** The name an entry shows: the merchant or counterparty label, else the institution (typed, digit-free values only). */
    fun label(r: ParsedRecord): String? = r.merchant ?: r.institution

    fun channel(r: ParsedRecord) = if (r.source == CaptureSource.SMS) CHANNEL_SMS else CHANNEL_PUSH

    /** [confirmedByUser]: the user looked at it and saved it, so it does not wait for review. */
    fun expense(
        id: String,
        r: ParsedRecord,
        now: Long,
        confirmedByUser: Boolean = false,
    ) = ExpenseEntity(
        id = id,
        amountMinor = r.amountMinor,
        currency = r.currency,
        occurredAtMillis = r.occurredAtMillis,
        merchant = label(r),
        categoryId = null,
        accountId = null,
        notes = null,
        pending = false,
        source = channel(r),
        type = typeOf(r.kind),
        status = if (!confirmedByUser && needsReview(r)) "PENDING_REVIEW" else "CONFIRMED",
        createdAtMillis = now,
        updatedAtMillis = now,
        timeKnown = r.timeKnown,
        paymentMethod = r.paymentMethod,
        instrumentLast4 = r.instrumentLast4,
    )

    fun source(
        id: String,
        expenseId: String,
        r: ParsedRecord,
        localId: String,
        receivedAt: Long,
    ) = ExpenseSourceEntity(
        id = id,
        expenseId = expenseId,
        channel = channel(r),
        localSourceId = localId,
        receivedAtMillis = receivedAt,
        senderId = r.senderId,
        instrumentLast4 = r.instrumentLast4,
    )

    /** The server's old mapping: money in is INCOME, money out to a person is a TRANSFER, the rest by what it is. */
    fun typeOf(kind: TxKind): String =
        when (kind) {
            TxKind.PURCHASE, TxKind.FEE -> "EXPENSE"
            TxKind.TRANSFER_OUT -> "TRANSFER"
            TxKind.TRANSFER_IN, TxKind.INCOME -> "INCOME"
            TxKind.REFUND -> "REFUND"
            TxKind.CASH_WITHDRAWAL -> "CASH_WITHDRAWAL"
        }

    /** Incoming money (a spoofed sender could claim it), unusually large amounts and any other currency wait for the user. */
    fun needsReview(r: ParsedRecord) =
        r.kind in setOf(TxKind.INCOME, TxKind.TRANSFER_IN, TxKind.REFUND) ||
            r.currency != BASE_CURRENCY ||
            r.amountMinor > REVIEW_CEILING_MINOR
}
