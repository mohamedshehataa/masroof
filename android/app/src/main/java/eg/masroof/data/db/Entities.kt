package eg.masroof.data.db

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
) {
    override fun toString() = "ReviewItemEntity(id=$id, reason=$reason)"
}

const val REVIEW_RETENTION_DAYS = 30L

/** Items created before this instant are expired: they are purged, and never shown even before the purge ran. */
fun reviewCutoff(nowMillis: Long): Long = nowMillis - REVIEW_RETENTION_DAYS * 24L * 60 * 60 * 1000
