package eg.masroof.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** The merchant text and category of one live entry: what a rule's key is matched against. Never message text. */
data class MerchantCategoryRow(
    val id: String,
    val merchant: String?,
    val categoryId: String?,
)

/**
 * Category editing and merchant rules (STORY-207). Writes `category.emoji` and the editable columns with plain UPDATEs, since
 * [LookupDao.upsertCategories] never writes the emoji. Schema unchanged; every statement is one string literal.
 */
@Dao
abstract class CategoryRulesDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCategory(item: CategoryEntity)

    @Query("SELECT * FROM category ORDER BY sortOrder, nameEn")
    abstract suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    abstract suspend fun findCategory(id: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM category WHERE parentId IS :parentId")
    abstract suspend fun maxSortOrder(parentId: String?): Int

    @Query("UPDATE category SET nameEn = :nameEn, nameAr = :nameAr, emoji = :emoji WHERE id = :id")
    abstract suspend fun updateDetails(
        id: String,
        nameEn: String,
        nameAr: String,
        emoji: String?,
    ): Int

    @Query("UPDATE category SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): Int

    @Query("UPDATE category SET sortOrder = :sortOrder WHERE id = :id")
    abstract suspend fun setSortOrder(
        id: String,
        sortOrder: Int,
    ): Int

    /** Entries (deleted ones too), sub-categories, budgets and rules that still point at the category. */
    @Query(
        """SELECT (SELECT COUNT(*) FROM expense WHERE categoryId = :id) + (SELECT COUNT(*) FROM category WHERE parentId = :id)
            + (SELECT COUNT(*) FROM budget WHERE categoryId = :id) + (SELECT COUNT(*) FROM merchant_rule WHERE categoryId = :id)""",
    )
    abstract suspend fun usageCount(id: String): Int

    @Query("DELETE FROM category WHERE id = :id AND system = 0")
    abstract suspend fun deleteCustom(id: String): Int

    @Query("SELECT * FROM merchant_rule WHERE merchantKey = :key LIMIT 1")
    abstract suspend fun ruleFor(key: String): MerchantRuleEntity?

    @Query("DELETE FROM merchant_rule WHERE merchantKey = :key")
    abstract suspend fun deleteRuleByKey(key: String): Int

    @Query("DELETE FROM merchant_rule WHERE id = :id")
    abstract suspend fun deleteRule(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRule(row: MerchantRuleEntity)

    /** One rule per key: a new category for a known merchant replaces the old rule. */
    @Transaction
    open suspend fun saveRule(row: MerchantRuleEntity) {
        deleteRuleByKey(row.merchantKey)
        insertRule(row)
    }

    @Query("SELECT id, merchant, categoryId FROM expense WHERE deletedAtMillis IS NULL AND merchant IS NOT NULL")
    abstract suspend fun liveMerchantRows(): List<MerchantCategoryRow>

    @Query("UPDATE expense SET categoryId = :categoryId, updatedAtMillis = :now WHERE id = :id AND deletedAtMillis IS NULL")
    abstract suspend fun setEntryCategory(
        id: String,
        categoryId: String?,
        now: Long,
    ): Int
}
