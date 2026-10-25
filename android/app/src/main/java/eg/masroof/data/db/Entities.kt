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
 * One filtered bank SMS waiting to be uploaded. [smsId] is stable for the same message (live and backfill), so a
 * re-insert is a no-op and the server dedups on (deviceId, smsId). The body is deleted once the server acknowledged it.
 */
@Entity(tableName = "sms_outbox")
data class SmsOutboxEntity(
    @PrimaryKey val smsId: String,
    val sender: String,
    val body: String,
    val receivedAtMillis: Long,
    val attempts: Int = 0,
    val failed: Boolean = false,
    val createdAtMillis: Long,
) {
    override fun toString() = "SmsOutboxEntity(smsId=$smsId)"
}
