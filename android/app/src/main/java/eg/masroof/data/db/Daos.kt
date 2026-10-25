package eg.masroof.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expense ORDER BY occurredAtMillis DESC, id")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expense ORDER BY occurredAtMillis DESC, id LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query(
        "SELECT COALESCE(SUM(amountMinor), 0) FROM expense WHERE occurredAtMillis >= :from AND occurredAtMillis < :to",
    )
    fun observeTotal(
        from: Long,
        to: Long,
    ): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ExpenseEntity)

    @Query("DELETE FROM expense WHERE id = :id")
    suspend fun delete(id: String)

    /** Replace the server-known rows; local pending rows are never touched by a refresh. */
    @Query("DELETE FROM expense WHERE pending = 0")
    suspend fun deleteSynced()

    @Transaction
    suspend fun replaceSynced(items: List<ExpenseEntity>) {
        deleteSynced()
        upsert(items)
    }
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE failed = 0 ORDER BY createdAtMillis")
    suspend fun pending(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE failed = 0")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE clientId = :clientId")
    suspend fun bumpAttempts(clientId: String)

    @Query("UPDATE outbox SET failed = 1 WHERE clientId = :clientId")
    suspend fun markFailed(clientId: String)

    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    suspend fun delete(clientId: String)

    @Query("SELECT * FROM outbox")
    suspend fun all(): List<OutboxEntity>
}

@Dao
interface LookupDao {
    @Query("SELECT * FROM category ORDER BY sortOrder, nameEn")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM account ORDER BY label")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(items: List<AccountEntity>)
}

@Database(
    entities = [ExpenseEntity::class, CategoryEntity::class, AccountEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao

    abstract fun outbox(): OutboxDao

    abstract fun lookups(): LookupDao

    /** Local-first write: the pending expense and its outbox row are committed together. */
    @Transaction
    open suspend fun enqueue(
        expense: ExpenseEntity,
        outbox: OutboxEntity,
    ) {
        expenses().upsert(expense)
        outbox().insert(outbox)
    }
}
