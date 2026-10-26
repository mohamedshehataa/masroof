package eg.masroof.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached expense. [pending] rows were created on this device and are still waiting in the outbox. */
@Entity(tableName = "expense")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    val merchant: String?,
    val categoryId: String?,
    val accountId: String?,
    val notes: String?,
    val pending: Boolean,
    val failed: Boolean = false,
    /** Channel that produced the entry ("SMS", "MANUAL" ...); null for rows cached before sources existed. */
    val source: String? = null,
    /** "EXPENSE" or "INCOME" (received money is listed, never added to spending). */
    @ColumnInfo(defaultValue = "'EXPENSE'") val type: String = "EXPENSE",
    /** The server's review state: "PENDING_REVIEW" for received money that waits for the household's OK. */
    @ColumnInfo(defaultValue = "'CONFIRMED'") val status: String = "CONFIRMED",
)

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val nameEn: String,
    val nameAr: String,
    val sortOrder: Int,
)

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: String,
    val label: String,
    val currency: String,
)

/** One manual entry waiting to be uploaded. [clientId] doubles as the idempotency key. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val clientId: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    val categoryId: String?,
    val accountId: String?,
    val notes: String?,
    val attempts: Int = 0,
    val failed: Boolean = false,
    val createdAtMillis: Long,
)

/**
 * One structured transaction parsed on the device, waiting to be uploaded (REQ-SMS-060). It holds NO message text: only
 * typed fields and a digit-free [merchant] label. [localId] is a hash computed on the device, so a re-insert is a no-op
 * and the server dedups on (deviceId, localId).
 */
@Entity(tableName = "parsed_outbox")
data class ParsedOutboxEntity(
    @PrimaryKey val localId: String,
    val source: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    val instrumentLast4: String?,
    val senderId: String,
    val institution: String?,
    val paymentMethod: String?,
    val merchant: String?,
    val attempts: Int = 0,
    val failed: Boolean = false,
    val createdAtMillis: Long,
    /** When an upload of this row started (0 = not being sent). Replacing a row is only allowed while this is unset or stale. */
    @ColumnInfo(defaultValue = "0") val claimedAtMillis: Long = 0L,
)

/**
 * A message the engine could not turn into a record, or that looked secret. The text lives ONLY here, on the phone
 * (digit runs of a secret one are masked); nothing is uploaded unless the user saves an expense from it. Cleared on
 * logout and after [REVIEW_RETENTION_DAYS] days.
 */
@Entity(tableName = "review_item")
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val source: String,
    val sender: String,
    val text: String,
    val reason: String,
    val receivedAtMillis: Long,
    val createdAtMillis: Long,
    // The engine's SUGGESTION (round 15): pre-fills the one-tap "Save". Typed fields only, no text; null when nothing was extracted.
    val sugKind: String? = null,
    val sugAmountMinor: Long? = null,
    val sugCurrency: String? = null,
    val sugOccurredAtMillis: Long? = null,
    val sugLast4: String? = null,
    val sugSenderId: String? = null,
    val sugInstitution: String? = null,
    val sugPaymentMethod: String? = null,
    val sugMerchant: String? = null,
    // Template learning: the digit-free shape of the message and its number slots, used only if the user confirms the item.
    val shape: String? = null,
    val slots: String? = null,
) {
    override fun toString() = "ReviewItemEntity(id=$id, reason=$reason)"
}

/**
 * A message format the user confirmed on this phone (round 15). [shape] holds typed slots and plain banking words only: no
 * digit, no name. A later message of the same [sender] with exactly this shape is recorded without asking, with the confirmed
 * [kind]; [amountSlot] / [last4Slot] (-1 = the engine's own pick) remember which number slot the user said held the amount or
 * the last four. Never uploaded; cleared on logout.
 */
@Entity(tableName = "learned_template")
data class LearnedTemplateEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val shape: String,
    val kind: String,
    val amountSlot: Int,
    val last4Slot: Int,
    val createdAtMillis: Long,
)

const val REVIEW_RETENTION_DAYS = 30L

/** Items created before this instant are expired: they are purged, and never shown even before the purge ran. */
fun reviewCutoff(nowMillis: Long): Long = nowMillis - REVIEW_RETENTION_DAYS * 24L * 60 * 60 * 1000

/**
 * An expense that the phone recorded by itself from an SMS or app notification (a proven family or a learned format): it drives
 * the "Recorded ... Undo" notification and the "Captured" chip for [CAPTURED_CHIP_DAYS] days. Typed fields only, never message text.
 * [expenseId] is the server expense once it was uploaded (needed to Undo through the API); [templateId] is the learned format
 * that recorded it, if any, so Undo can offer "Stop learning this format?".
 */
@Entity(tableName = "captured_tx")
data class CapturedTxEntity(
    @PrimaryKey val localId: String,
    val expenseId: String?,
    val amountMinor: Long,
    val currency: String,
    val label: String?,
    val source: String,
    val templateId: String?,
    val capturedAtMillis: Long,
    // The user pressed Undo while the upload was (or may have been) in flight: the expense is deleted as soon as its id is known.
    @ColumnInfo(defaultValue = "0") val undoRequested: Boolean = false,
    // What the record was (a TxKind name), when the phone RECEIVED the message and the account tail: lets the pipeline see that a
    // second channel (bank SMS + app notification) announced the same transfer. Rows made before version 8 get them from the outbox.
    val kind: String? = null,
    @ColumnInfo(defaultValue = "0") val receivedAtMillis: Long = 0L,
    // This record is already matched with one record of the other channel: it takes part in no further match.
    @ColumnInfo(defaultValue = "0") val paired: Boolean = false,
    val instrumentLast4: String? = null,
) {
    override fun toString() = "CapturedTxEntity(localId=$localId)"
}

const val CAPTURED_CHIP_DAYS = 7L

/** Captured rows older than this are no longer shown with a chip (and are purged). */
fun capturedCutoff(nowMillis: Long): Long = nowMillis - CAPTURED_CHIP_DAYS * 24L * 60 * 60 * 1000
