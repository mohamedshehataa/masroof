package eg.masroof.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// v10 (STORY-201): plain DAOs for the money-life tables: insert / update / delete / observe-all / observe-by-id. The feature
// stories add repositories and richer queries on top; an insert that breaks a unique index or a foreign key throws.

/** One entry's flow facts for the balance maths (no merchant, no note, no message text). */
data class AccountFlowRow(
    val accountId: String,
    val type: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
)

@Dao
interface AccountBalanceDao {
    /** Every live entry that names an account; failed and soft-deleted ones never moved money. */
    @Query(
        """SELECT accountId, type, status, amountMinor, currency, occurredAtMillis FROM expense
            WHERE accountId IS NOT NULL AND failed = 0 AND deletedAtMillis IS NULL""",
    )
    fun observeFlowRows(): Flow<List<AccountFlowRow>>

    /** Edits an account in place, including the v10 card columns and emoji that an upsert never writes. */
    @Suppress("LongParameterList")
    @Query(
        """UPDATE account SET label = :label, kind = :kind, institution = :institution, last4 = :last4, emoji = :emoji,
            creditLimitMinor = :limit, statementDay = :statementDay, dueDay = :dueDay WHERE id = :id""",
    )
    suspend fun updateAccountDetails(
        id: String,
        label: String,
        kind: String?,
        institution: String?,
        last4: String?,
        emoji: String?,
        limit: Long?,
        statementDay: Int?,
        dueDay: Int?,
    )

    @Query("UPDATE account SET archived = :archived WHERE id = :id")
    suspend fun setAccountArchived(
        id: String,
        archived: Boolean,
    )

    @Insert
    suspend fun insert(row: AccountBalanceEntity)

    @Update
    suspend fun update(row: AccountBalanceEntity)

    @Delete
    suspend fun delete(row: AccountBalanceEntity)

    @Query("SELECT * FROM account_balance ORDER BY atMillis DESC, id")
    fun observeAll(): Flow<List<AccountBalanceEntity>>

    @Query("SELECT * FROM account_balance WHERE id = :id")
    fun observeById(id: String): Flow<AccountBalanceEntity?>
}

@Dao
interface BudgetDao {
    @Insert
    suspend fun insert(row: BudgetEntity)

    @Update
    suspend fun update(row: BudgetEntity)

    @Delete
    suspend fun delete(row: BudgetEntity)

    @Query("SELECT * FROM budget ORDER BY createdAtMillis, id")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget WHERE id = :id")
    fun observeById(id: String): Flow<BudgetEntity?>
}

@Dao
interface InstallmentPlanDao {
    @Insert
    suspend fun insert(row: InstallmentPlanEntity)

    @Update
    suspend fun update(row: InstallmentPlanEntity)

    @Delete
    suspend fun delete(row: InstallmentPlanEntity)

    @Query("SELECT * FROM installment_plan ORDER BY createdAtMillis, id")
    fun observeAll(): Flow<List<InstallmentPlanEntity>>

    @Query("SELECT * FROM installment_plan WHERE id = :id")
    fun observeById(id: String): Flow<InstallmentPlanEntity?>
}

@Dao
interface InstallmentPaymentDao {
    @Insert
    suspend fun insert(row: InstallmentPaymentEntity)

    @Update
    suspend fun update(row: InstallmentPaymentEntity)

    @Delete
    suspend fun delete(row: InstallmentPaymentEntity)

    /** Unpaid payments of active plans in [currency]: "Installments left". */
    @Query(
        """SELECT COALESCE(SUM(p.amountMinor), 0) FROM installment_payment p JOIN installment_plan pl ON pl.id = p.planId
            WHERE p.paidAtMillis IS NULL AND pl.status = 'ACTIVE' AND pl.currency = :currency""",
    )
    fun observeUnpaidTotal(currency: String): Flow<Long>

    @Query("SELECT * FROM installment_payment ORDER BY dueAtMillis, id")
    fun observeAll(): Flow<List<InstallmentPaymentEntity>>

    @Query("SELECT * FROM installment_payment WHERE id = :id")
    fun observeById(id: String): Flow<InstallmentPaymentEntity?>
}

@Dao
interface SubscriptionDao {
    @Insert
    suspend fun insert(row: SubscriptionEntity)

    @Update
    suspend fun update(row: SubscriptionEntity)

    @Delete
    suspend fun delete(row: SubscriptionEntity)

    @Query("SELECT * FROM subscription ORDER BY nextRenewalAtMillis, id")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription WHERE id = :id")
    fun observeById(id: String): Flow<SubscriptionEntity?>
}

@Dao
interface SavingsGoalDao {
    @Insert
    suspend fun insert(row: SavingsGoalEntity)

    @Update
    suspend fun update(row: SavingsGoalEntity)

    @Delete
    suspend fun delete(row: SavingsGoalEntity)

    @Query("SELECT * FROM savings_goal ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goal WHERE id = :id")
    fun observeById(id: String): Flow<SavingsGoalEntity?>
}

@Dao
interface GoalMovementDao {
    @Insert
    suspend fun insert(row: GoalMovementEntity)

    @Update
    suspend fun update(row: GoalMovementEntity)

    @Delete
    suspend fun delete(row: GoalMovementEntity)

    @Query("SELECT * FROM goal_movement ORDER BY atMillis DESC, id")
    fun observeAll(): Flow<List<GoalMovementEntity>>

    @Query("SELECT * FROM goal_movement WHERE id = :id")
    fun observeById(id: String): Flow<GoalMovementEntity?>
}

@Dao
interface AssetDao {
    @Insert
    suspend fun insert(row: AssetEntity)

    @Update
    suspend fun update(row: AssetEntity)

    @Delete
    suspend fun delete(row: AssetEntity)

    @Query("SELECT * FROM asset ORDER BY createdAtMillis, id")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM asset WHERE id = :id")
    fun observeById(id: String): Flow<AssetEntity?>
}

@Dao
interface AssetLotDao {
    @Insert
    suspend fun insert(row: AssetLotEntity)

    @Update
    suspend fun update(row: AssetLotEntity)

    @Delete
    suspend fun delete(row: AssetLotEntity)

    @Query("SELECT * FROM asset_lot ORDER BY atMillis, id")
    fun observeAll(): Flow<List<AssetLotEntity>>

    @Query("SELECT * FROM asset_lot WHERE id = :id")
    fun observeById(id: String): Flow<AssetLotEntity?>
}

@Dao
interface MetalPriceDao {
    @Insert
    suspend fun insert(row: MetalPriceEntity)

    @Update
    suspend fun update(row: MetalPriceEntity)

    @Delete
    suspend fun delete(row: MetalPriceEntity)

    @Query("SELECT * FROM metal_price ORDER BY atMillis DESC, id")
    fun observeAll(): Flow<List<MetalPriceEntity>>

    @Query("SELECT * FROM metal_price WHERE id = :id")
    fun observeById(id: String): Flow<MetalPriceEntity?>
}

@Dao
interface MerchantRuleDao {
    @Insert
    suspend fun insert(row: MerchantRuleEntity)

    @Update
    suspend fun update(row: MerchantRuleEntity)

    @Delete
    suspend fun delete(row: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rule ORDER BY merchantKey")
    fun observeAll(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rule WHERE id = :id")
    fun observeById(id: String): Flow<MerchantRuleEntity?>
}

@Dao
interface DebtDao {
    @Insert
    suspend fun insert(row: DebtEntity)

    @Update
    suspend fun update(row: DebtEntity)

    @Delete
    suspend fun delete(row: DebtEntity)

    @Query("SELECT * FROM debt ORDER BY createdAtMillis, id")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debt WHERE id = :id")
    fun observeById(id: String): Flow<DebtEntity?>
}

@Dao
interface NetWorthSnapshotDao {
    @Insert
    suspend fun insert(row: NetWorthSnapshotEntity)

    @Update
    suspend fun update(row: NetWorthSnapshotEntity)

    @Delete
    suspend fun delete(row: NetWorthSnapshotEntity)

    @Query("SELECT * FROM net_worth_snapshot WHERE cairoDate = :cairoDate")
    suspend fun findByDate(cairoDate: String): NetWorthSnapshotEntity?

    @Query("SELECT * FROM net_worth_snapshot ORDER BY cairoDate DESC")
    fun observeAll(): Flow<List<NetWorthSnapshotEntity>>

    @Query("SELECT * FROM net_worth_snapshot WHERE id = :id")
    fun observeById(id: String): Flow<NetWorthSnapshotEntity?>
}
