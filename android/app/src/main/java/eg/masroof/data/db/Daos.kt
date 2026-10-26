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
interface ParsedOutboxDao {
    /** IGNORE: the same message seen live and in a backfill is stored once. Returns the new row id, or -1 if it existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ParsedOutboxEntity): Long

    @Query("SELECT * FROM parsed_outbox WHERE failed = 0 ORDER BY occurredAtMillis")
    suspend fun pending(): List<ParsedOutboxEntity>

    @Query("UPDATE parsed_outbox SET attempts = attempts + 1 WHERE localId = :localId")
    suspend fun bumpAttempts(localId: String)

    @Query("UPDATE parsed_outbox SET failed = 1 WHERE localId = :localId")
    suspend fun markFailed(localId: String)

    @Query("DELETE FROM parsed_outbox WHERE localId = :localId")
    suspend fun delete(localId: String)

    /** Permanently failed rows go after the retention window. */
    @Query("DELETE FROM parsed_outbox WHERE failed = 1 AND createdAtMillis < :olderThanMillis")
    suspend fun purgeFailedBefore(olderThanMillis: Long)

    @Query("SELECT COUNT(*) FROM parsed_outbox WHERE failed = 0")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM parsed_outbox")
    suspend fun countAll(): Int

    @Query("SELECT * FROM parsed_outbox")
    suspend fun all(): List<ParsedOutboxEntity>
}

@Dao
interface ReviewDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ReviewItemEntity): Long

    @Query("SELECT * FROM review_item WHERE createdAtMillis >= :cutoff ORDER BY receivedAtMillis DESC")
    fun observeAll(cutoff: Long): Flow<List<ReviewItemEntity>>

    @Query("SELECT COUNT(*) FROM review_item WHERE createdAtMillis >= :cutoff")
    fun observeCount(cutoff: Long): Flow<Int>

    @Query("SELECT * FROM review_item WHERE id = :id")
    suspend fun find(id: String): ReviewItemEntity?

    @Query("DELETE FROM review_item WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM review_item WHERE createdAtMillis < :olderThanMillis")
    suspend fun purgeBefore(olderThanMillis: Long)

    @Query("SELECT * FROM review_item")
    suspend fun all(): List<ReviewItemEntity>
}

@Dao
interface LearnedTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LearnedTemplateEntity)

    @Query("SELECT * FROM learned_template WHERE sender = :sender AND shape = :shape LIMIT 1")
    suspend fun find(
        sender: String,
        shape: String,
    ): LearnedTemplateEntity?

    @Query("SELECT COUNT(*) FROM learned_template")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM learned_template")
    suspend fun clear()

    @Query("SELECT * FROM learned_template")
    suspend fun all(): List<LearnedTemplateEntity>
}

@Database(
    entities =
        [
            ExpenseEntity::class,
            CategoryEntity::class,
            AccountEntity::class,
            OutboxEntity::class,
            ParsedOutboxEntity::class,
            ReviewItemEntity::class,
            LearnedTemplateEntity::class,
        ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao

    abstract fun outbox(): OutboxDao

    abstract fun lookups(): LookupDao

    abstract fun parsedOutbox(): ParsedOutboxDao

    abstract fun reviews(): ReviewDao

    abstract fun learnedTemplates(): LearnedTemplateDao

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

private const val CREATE_SMS_OUTBOX = """
CREATE TABLE IF NOT EXISTS sms_outbox (
  smsId TEXT NOT NULL,
  sender TEXT NOT NULL,
  body TEXT NOT NULL,
  receivedAtMillis INTEGER NOT NULL,
  attempts INTEGER NOT NULL,
  failed INTEGER NOT NULL,
  createdAtMillis INTEGER NOT NULL,
  PRIMARY KEY(smsId)
)
"""

private const val CREATE_PARSED_OUTBOX = """
CREATE TABLE IF NOT EXISTS parsed_outbox (
  localId TEXT NOT NULL,
  source TEXT NOT NULL,
  kind TEXT NOT NULL,
  amountMinor INTEGER NOT NULL,
  currency TEXT NOT NULL,
  occurredAtMillis INTEGER NOT NULL,
  instrumentLast4 TEXT,
  senderId TEXT NOT NULL,
  institution TEXT,
  paymentMethod TEXT,
  merchant TEXT,
  attempts INTEGER NOT NULL,
  failed INTEGER NOT NULL,
  createdAtMillis INTEGER NOT NULL,
  PRIMARY KEY(localId)
)
"""

private const val CREATE_REVIEW_ITEM = """
CREATE TABLE IF NOT EXISTS review_item (
  id TEXT NOT NULL,
  source TEXT NOT NULL,
  sender TEXT NOT NULL,
  text TEXT NOT NULL,
  reason TEXT NOT NULL,
  receivedAtMillis INTEGER NOT NULL,
  createdAtMillis INTEGER NOT NULL,
  PRIMARY KEY(id)
)
"""

private const val CREATE_LEARNED_TEMPLATE = """
CREATE TABLE IF NOT EXISTS learned_template (
  id TEXT NOT NULL,
  sender TEXT NOT NULL,
  shape TEXT NOT NULL,
  kind TEXT NOT NULL,
  amountSlot INTEGER NOT NULL,
  last4Slot INTEGER NOT NULL,
  createdAtMillis INTEGER NOT NULL,
  PRIMARY KEY(id)
)
"""

/**
 * v4 -> v5 (round 15): a review item carries the engine's typed suggestion plus its digit-free shape, and the user's confirmed
 * message formats are stored on the phone as `learned_template` rows.
 */
val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // One literal statement per column: SQL is never assembled from strings (semgrep CWE-89 rules).
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugKind TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugAmountMinor INTEGER")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugCurrency TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugOccurredAtMillis INTEGER")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugLast4 TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugSenderId TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugInstitution TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugPaymentMethod TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN sugMerchant TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN shape TEXT")
            db.execSQL("ALTER TABLE review_item ADD COLUMN slots TEXT")
            db.execSQL(CREATE_LEARNED_TEMPLATE)
        }
    }

/**
 * v3 -> v4: SMS text no longer leaves or lingers on the phone. The v3 `sms_outbox` (which held redacted text) is dropped
 * and replaced by a typed `parsed_outbox` (no text) and the local `review_item` list.
 */
val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS sms_outbox")
            db.execSQL(CREATE_PARSED_OUTBOX)
            db.execSQL(CREATE_REVIEW_ITEM)
        }
    }

/** v2 -> v3: where an expense came from, and the SMS upload queue. Keeps the manual-entry outbox intact. */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE expense ADD COLUMN source TEXT")
            db.execSQL(CREATE_SMS_OUTBOX)
        }
    }
