package eg.masroof.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// v10 (STORY-201, EPIC-18): the money-life tables. Money is Long minor units + ISO-4217 text, times are UTC epoch millis,
// enums are TEXT (the allowed values are listed per field), booleans are INTEGER 0/1, rates are integer basis points.
// No message text and no account number is stored in any of them.

/** A known balance of an account at a moment. The current balance is the latest one plus entry flows after it. */
@Entity(
    tableName = "account_balance",
    foreignKeys =
        [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId")],
)
data class AccountBalanceEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val balanceMinor: Long,
    val currency: String,
    val atMillis: Long,
    /** MANUAL | SMS */
    val source: String,
    val createdAtMillis: Long,
)

/** A spending limit. [categoryId] null means all spending. */
@Entity(
    tableName = "budget",
    foreignKeys =
        [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("categoryId")],
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val categoryId: String?,
    /** MONTHLY | WEEKLY */
    val period: String,
    val amountMinor: Long,
    val currency: String,
    @ColumnInfo(defaultValue = "0") val rollover: Boolean = false,
    /** Percentages that raise an alert, comma separated. */
    @ColumnInfo(defaultValue = "'50,80,100'") val alertThresholds: String = "50,80,100",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** An installment plan (valU, Halan ...) or a gam3eya (rotating savings circle; the payout columns). [provider] is free text. */
@Entity(tableName = "installment_plan")
data class InstallmentPlanEntity(
    @PrimaryKey val id: String,
    val title: String,
    val provider: String,
    val categoryId: String?,
    val accountId: String?,
    val currency: String,
    val purchasePriceMinor: Long?,
    val downPaymentMinor: Long,
    val monthlyAmountMinor: Long,
    val paymentCount: Int,
    val firstDueAtMillis: Long,
    val reminderDaysBefore: Int,
    /** ACTIVE | COMPLETED | ARCHIVED */
    val status: String,
    val notes: String?,
    val createdAtMillis: Long,
    val completedAtMillis: Long?,
    val payoutAtMillis: Long?,
    val payoutAmountMinor: Long?,
    val payoutReceivedAtMillis: Long?,
)

/** One due payment of a plan; unique per (plan, seq). Deleting the plan deletes it; deleting the linked expense unlinks it. */
@Entity(
    tableName = "installment_payment",
    foreignKeys =
        [
            ForeignKey(
                entity = InstallmentPlanEntity::class,
                parentColumns = ["id"],
                childColumns = ["planId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(entity = ExpenseEntity::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.SET_NULL),
        ],
    indices = [Index(value = ["planId", "seq"], unique = true), Index("expenseId")],
)
data class InstallmentPaymentEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val seq: Int,
    val dueAtMillis: Long,
    val amountMinor: Long,
    val paidAtMillis: Long?,
    val expenseId: String?,
)

/** A recurring subscription, bill or income (salary, a certificate payout). */
@Entity(tableName = "subscription")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    /** SUBSCRIPTION | BILL | INCOME */
    val kind: String,
    val name: String,
    val amountMinor: Long,
    val currency: String,
    /** WEEKLY | MONTHLY | QUARTERLY | YEARLY */
    val cadence: String,
    val nextRenewalAtMillis: Long,
    val categoryId: String?,
    val accountId: String?,
    val merchantKey: String?,
    val reminderDaysBefore: Int,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    val notes: String?,
    val createdAtMillis: Long,
)

@Entity(tableName = "savings_goal")
data class SavingsGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String?,
    val targetMinor: Long,
    val currency: String,
    val targetAtMillis: Long?,
    val sortOrder: Int,
    val createdAtMillis: Long,
    val completedAtMillis: Long?,
    val archivedAtMillis: Long?,
)

/** Money put into (positive) or taken out of (negative) a goal. */
@Entity(
    tableName = "goal_movement",
    foreignKeys =
        [ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("goalId")],
)
data class GoalMovementEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val amountMinor: Long,
    val atMillis: Long,
    val note: String?,
)

/** Something owned: gold, a certificate, a fund, property ... Gold and silver quantity comes from [AssetLotEntity] rows. */
@Entity(
    tableName = "asset",
    foreignKeys =
        [ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("goalId")],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    /** GOLD | SILVER | CERTIFICATE | FUND | INVESTMENT | PROPERTY | VEHICLE | BUSINESS | OTHER */
    val kind: String,
    val name: String,
    val currency: String,
    /** A manual value. */
    val valueMinor: Long?,
    /** Gold / silver purity. */
    val karat: Int?,
    val principalMinor: Long?,
    /** Certificate or fund rate in basis points. */
    val annualRateBp: Int?,
    val startAtMillis: Long?,
    val maturityAtMillis: Long?,
    val goalId: String?,
    @ColumnInfo(defaultValue = "0") val hiddenFromTotals: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Gold bought in pieces and sold later. [quantityMilli] is grams (or units) x 1000. */
@Entity(
    tableName = "asset_lot",
    foreignKeys =
        [
            ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
            ForeignKey(entity = ExpenseEntity::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.SET_NULL),
        ],
    indices = [Index("assetId"), Index("expenseId")],
)
data class AssetLotEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    /** BUY | SELL */
    val side: String,
    val quantityMilli: Long,
    val totalMinor: Long,
    val currency: String,
    val atMillis: Long,
    val expenseId: String?,
    val note: String?,
)

/** A metal price the user typed. */
@Entity(tableName = "metal_price")
data class MetalPriceEntity(
    @PrimaryKey val id: String,
    /** GOLD | SILVER */
    val metal: String,
    val karat: Int,
    val pricePerGramMinor: Long,
    val currency: String,
    val atMillis: Long,
)

/** "Always file this merchant under this category". [merchantKey] is normalised and digit-free. */
@Entity(
    tableName = "merchant_rule",
    foreignKeys =
        [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["merchantKey"], unique = true), Index("categoryId")],
)
data class MerchantRuleEntity(
    @PrimaryKey val id: String,
    val merchantKey: String,
    val categoryId: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "debt")
data class DebtEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** LOAN | CREDIT_CARD | PERSON | OTHER */
    val kind: String,
    /** I_OWE | OWED_TO_ME */
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val dueAtMillis: Long?,
    val settledAtMillis: Long?,
    val notes: String?,
    val createdAtMillis: Long,
)

/** One net-worth point per Cairo day. [breakdown] is `KIND=minor;KIND=minor`. */
@Entity(tableName = "net_worth_snapshot", indices = [Index(value = ["cairoDate"], unique = true)])
data class NetWorthSnapshotEntity(
    @PrimaryKey val id: String,
    /** yyyy-MM-dd in Africa/Cairo */
    val cairoDate: String,
    val assetsMinor: Long,
    val debtsMinor: Long,
    val netMinor: Long,
    val currency: String,
    val breakdown: String,
    val createdAtMillis: Long,
)
