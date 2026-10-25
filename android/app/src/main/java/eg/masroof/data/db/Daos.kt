package eg.masroof.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expense ORDER BY occurredAtMillis DESC, id")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expense ORDER BY occurredAtMillis DESC, id LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query(
        "SELECT COALESCE(SUM(amountMinor), 0) FROM expense WHERE failed = 0 AND occurredAtMillis >= :from AND occurredAtMillis < :to",
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

    @Query("UPDATE expense SET failed = :failed WHERE id = :id")
    suspend fun setFailed(
        id: String,
        failed: Boolean,
    )

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

    @Query("UPDATE outbox SET failed = 0, attempts = 0 WHERE clientId = :clientId")
    suspend fun reset(clientId: String)

    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    suspend fun delete(clientId: String)

    /** Entries still being retried (not permanently failed). */
    @Query("SELECT COUNT(*) FROM outbox WHERE failed = 0")
    suspend fun countRetryable(): Int

    /** Pending plus failed: everything not yet on the server. */
    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun countAll(): Int

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

@Dao
interface SmsOutboxDao {
    /** IGNORE: the same SMS seen live and in a backfill is stored once. Returns the new row id, or -1 if it existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SmsOutboxEntity): Long

    @Query("SELECT * FROM sms_outbox WHERE failed = 0 ORDER BY receivedAtMillis")
    suspend fun pending(): List<SmsOutboxEntity>

    @Query("UPDATE sms_outbox SET attempts = attempts + 1 WHERE smsId = :smsId")
    suspend fun bumpAttempts(smsId: String)

    @Query("UPDATE sms_outbox SET failed = 1 WHERE smsId = :smsId")
    suspend fun markFailed(smsId: String)

    @Query("DELETE FROM sms_outbox WHERE smsId = :smsId")
    suspend fun delete(smsId: String)

    /** Bodies never linger: permanently failed rows go after the retention window. */
    @Query("DELETE FROM sms_outbox WHERE failed = 1 AND createdAtMillis < :olderThanMillis")
    suspend fun purgeFailedBefore(olderThanMillis: Long)

    @Query("SELECT COUNT(*) FROM sms_outbox WHERE failed = 0")
    suspend fun countPending(): Int

    @Query("SELECT * FROM sms_outbox")
    suspend fun all(): List<SmsOutboxEntity>
}

@Database(
    entities =
        [
            ExpenseEntity::class,
            CategoryEntity::class,
            AccountEntity::class,
            OutboxEntity::class,
            SmsOutboxEntity::class,
        ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao

    abstract fun outbox(): OutboxDao

    abstract fun lookups(): LookupDao

    abstract fun smsOutbox(): SmsOutboxDao

    /** Local-first write: the pending expense and its outbox row are committed together. */
    @Transaction
    open suspend fun enqueue(
        expense: ExpenseEntity,
        outbox: OutboxEntity,
    ) {
        expenses().upsert(expense)
        outbox().insert(outbox)
    }

    /** Swap the local pending row for the server's canonical row and drop the outbox entry, atomically. */
    @Transaction
    open suspend fun completeUpload(
        clientId: String,
        serverRow: ExpenseEntity,
    ) {
        expenses().delete(clientId)
        expenses().upsert(serverRow)
        outbox().delete(clientId)
    }

    /** The server permanently rejected the entry: keep it visible as failed, out of totals. */
    @Transaction
    open suspend fun markRejected(clientId: String) {
        outbox().markFailed(clientId)
        expenses().setFailed(clientId, true)
    }

    @Transaction
    open suspend fun resetFailed(clientId: String) {
        outbox().reset(clientId)
        expenses().setFailed(clientId, false)
    }

    @Transaction
    open suspend fun discard(clientId: String) {
        outbox().delete(clientId)
        expenses().delete(clientId)
    }
}

/** v2 -> v3: where an expense came from, and the SMS upload queue. Keeps the manual-entry outbox intact. */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE expense ADD COLUMN source TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sms_outbox (smsId TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, " +
                    "receivedAtMillis INTEGER NOT NULL, attempts INTEGER NOT NULL, failed INTEGER NOT NULL, " +
                    "createdAtMillis INTEGER NOT NULL, PRIMARY KEY(smsId))",
            )
        }
    }
