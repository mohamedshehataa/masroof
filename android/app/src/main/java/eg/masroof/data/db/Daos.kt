package eg.masroof.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

private const val DELETE_BATCH = 500

/** Fields the server does not know are kept from the stored row; fields the new row sets win. */
internal fun ExpenseEntity.keepLocalFrom(old: ExpenseEntity): ExpenseEntity =
    copy(
        serverId = serverId ?: old.serverId,
        createdAtMillis = if (old.createdAtMillis != 0L) old.createdAtMillis else createdAtMillis,
        updatedAtMillis = if (updatedAtMillis != 0L) updatedAtMillis else old.updatedAtMillis,
        deletedAtMillis = deletedAtMillis ?: old.deletedAtMillis,
        timeKnown = old.timeKnown,
        paymentMethod = paymentMethod ?: old.paymentMethod,
        instrumentLast4 = instrumentLast4 ?: old.instrumentLast4,
        paidByMemberId = paidByMemberId ?: old.paidByMemberId,
    )

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expense WHERE deletedAtMillis IS NULL ORDER BY occurredAtMillis DESC, id")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expense WHERE deletedAtMillis IS NULL ORDER BY occurredAtMillis DESC, id LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query(
        """SELECT COALESCE(SUM(amountMinor), 0) FROM expense WHERE failed = 0 AND deletedAtMillis IS NULL AND type = 'EXPENSE'
            AND status = 'CONFIRMED' AND currency = 'EGP' AND occurredAtMillis >= :from AND occurredAtMillis < :to""",
    )
    fun observeTotal(
        from: Long,
        to: Long,
    ): Flow<Long>

    // No INSERT OR REPLACE on expense: a replace deletes the row, which would reset the v9 columns and cascade-delete
    // `expense_source` rows (foreign keys are on). A write is an insert for a new id and an UPDATE for an existing one.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ExpenseEntity)

    @Update
    suspend fun update(item: ExpenseEntity)

    @Query("SELECT * FROM expense")
    suspend fun all(): List<ExpenseEntity>

    @Transaction
    suspend fun upsert(items: List<ExpenseEntity>) {
        val existing = all().associateBy { it.id }
        for (item in items) {
            val old = existing[item.id]
            if (old == null) insert(item) else update(item.keepLocalFrom(old))
        }
    }

    @Transaction
    suspend fun upsert(item: ExpenseEntity) {
        val old = find(item.id)
        if (old == null) insert(item) else update(item.keepLocalFrom(old))
    }

    @Query("SELECT * FROM expense WHERE id = :id")
    suspend fun find(id: String): ExpenseEntity?

    /** Soft delete for Undo (ADR-0009 D3): the row and its sources stay until the 30-day purge. Returns the rows changed. */
    @Query("UPDATE expense SET deletedAtMillis = :nowMillis, updatedAtMillis = :nowMillis WHERE id = :id AND deletedAtMillis IS NULL")
    suspend fun softDelete(
        id: String,
        nowMillis: Long,
    ): Int

    /** Undo of a soft delete. Returns the rows changed (0 when the row is gone or was never deleted). */
    @Query("UPDATE expense SET deletedAtMillis = NULL, updatedAtMillis = :nowMillis WHERE id = :id AND deletedAtMillis IS NOT NULL")
    suspend fun restore(
        id: String,
        nowMillis: Long,
    ): Int

    @Query("DELETE FROM expense_source WHERE expenseId IN (SELECT id FROM expense WHERE deletedAtMillis < :cutoffMillis)")
    suspend fun purgeSourcesDeletedBefore(cutoffMillis: Long): Int

    @Query("DELETE FROM expense WHERE deletedAtMillis < :cutoffMillis")
    suspend fun purgeRowsDeletedBefore(cutoffMillis: Long): Int

    /** Removes entries soft-deleted before [cutoffMillis] with their sources (receipts: STORY-174). Returns the entries removed. */
    @Transaction
    suspend fun purgeDeletedBefore(cutoffMillis: Long): Int {
        purgeSourcesDeletedBefore(cutoffMillis)
        return purgeRowsDeletedBefore(cutoffMillis)
    }

    /**
     * The second channel of one transfer knew more (the account tail, a name): an entry nobody edited takes it. One statement, so
     * an edit made meanwhile wins; `updatedAtMillis` is left alone (the pairing is not an edit). Returns the rows changed.
     */
    @Query(
        """UPDATE expense SET instrumentLast4 = :last4, merchant = COALESCE(:merchant, merchant)
            WHERE id = :id AND deletedAtMillis IS NULL AND updatedAtMillis = createdAtMillis""",
    )
    suspend fun enrichFromTwin(
        id: String,
        last4: String?,
        merchant: String?,
    ): Int

    @Query("DELETE FROM expense WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE expense SET failed = :failed WHERE id = :id")
    suspend fun setFailed(
        id: String,
        failed: Boolean,
    )

    @Query("UPDATE expense_source SET expenseId = :to WHERE expenseId = :from")
    suspend fun moveSources(
        from: String,
        to: String,
    )

    @Query("UPDATE receipt SET expenseId = :to WHERE expenseId = :from")
    suspend fun moveReceipts(
        from: String,
        to: String,
    )

    @Query("SELECT COUNT(*) FROM expense")
    suspend fun count(): Int

    @Query("SELECT id FROM expense WHERE pending = 0")
    suspend fun syncedIds(): List<String>

    /** Rows the server no longer lists are removed in small batches (SQLite allows few bound variables on old Android). */
    @Transaction
    suspend fun deleteAll(ids: List<String>) {
        ids.chunked(DELETE_BATCH).forEach { deleteBatch(it) }
    }

    @Query("DELETE FROM expense WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)

    /**
     * Refresh from the server: rows it lists are inserted or updated in place (local-only columns, `expense_source` and
     * receipts stay), rows it no longer lists are deleted; local pending rows are never touched by a refresh.
     */
    @Transaction
    suspend fun replaceSynced(items: List<ExpenseEntity>) {
        val keep = items.mapTo(HashSet()) { it.id }
        deleteAll(syncedIds().filter { it !in keep })
        upsert(items)
    }
}

/** The dedup link of an entry to the message (or manual save) that made it. Inserts abort on a duplicate `localSourceId`. */
@Dao
interface ExpenseSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ExpenseSourceEntity)

    @Query("SELECT COUNT(*) FROM expense_source WHERE localSourceId = :localSourceId")
    suspend fun countByLocalSourceId(localSourceId: String): Int

    @Query("SELECT expenseId FROM expense_source WHERE localSourceId = :localSourceId")
    suspend fun expenseIdOfLocalSource(localSourceId: String): String?

    @Query("SELECT * FROM expense_source")
    suspend fun all(): List<ExpenseSourceEntity>
}

@Dao
interface ReceiptDao {
    @Query("SELECT COUNT(*) FROM receipt")
    suspend fun count(): Int
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
    /**
     * The categories the server knows (pickers, uploads). The phone's own defaults (`system = 1`, ids `cat-...`) are not
     * server ids and are hidden here until the local-only screens use [observeAllCategories] (STORY-169+).
     */
    @Query("SELECT * FROM category WHERE system = 0 ORDER BY sortOrder, nameEn")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY sortOrder, nameEn")
    fun observeLabelCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE archived = 0 ORDER BY sortOrder, nameEn")
    fun observeAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM member ORDER BY sortOrder, displayName")
    fun observeMembers(): Flow<List<MemberEntity>>

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

    /**
     * Takes the row for an upload. One statement, so of two uploaders (or an uploader and a replacement) exactly one gets 1.
     * A claim older than [staleBefore] belongs to a request that died: it can be taken again.
     */
    @Query("SELECT * FROM parsed_outbox WHERE localId = :localId")
    suspend fun find(localId: String): ParsedOutboxEntity?

    @Query(
        """UPDATE parsed_outbox SET claimedAtMillis = :now, claims = claims + 1
            WHERE localId = :localId AND failed = 0 AND claimedAtMillis < :staleBefore""",
    )
    suspend fun claim(
        localId: String,
        now: Long,
        staleBefore: Long,
    ): Int

    /** Gives a claim back, but only the caller's own ([token] is the time it took the claim): a newer holder keeps its claim. */
    @Query("UPDATE parsed_outbox SET claimedAtMillis = 0 WHERE localId = :localId AND claimedAtMillis = :token")
    suspend fun release(
        localId: String,
        token: Long,
    )

    /**
     * Deletes a queued record, but only one that no upload ever started: a row that was claimed once may be on the server (its answer
     * can be lost), so it keeps its local id and is retried. Returns 1 when deleted, 0 otherwise.
     */
    @Query("DELETE FROM parsed_outbox WHERE localId = :localId AND claims = 0 AND claimedAtMillis < :staleBefore")
    suspend fun deleteIfUnclaimed(
        localId: String,
        staleBefore: Long,
    ): Int

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

    @Query("DELETE FROM learned_template WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM learned_template")
    suspend fun all(): List<LearnedTemplateEntity>
}

@Dao
interface CapturedDao {
    /** IGNORE: the same message accepted again never overwrites the row (it would lose its expense id). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: CapturedTxEntity): Long

    @Query(
        """SELECT * FROM captured_tx WHERE capturedAtMillis >= :cutoff AND undoRequested = 0
            AND COALESCE(templateId, '') != 'REVIEW_SAVED' ORDER BY capturedAtMillis DESC""",
    )
    fun observeRecent(cutoff: Long): Flow<List<CapturedTxEntity>>

    @Query("SELECT * FROM captured_tx WHERE localId = :localId")
    suspend fun find(localId: String): CapturedTxEntity?

    @Query("UPDATE captured_tx SET expenseId = :expenseId WHERE localId = :localId")
    suspend fun setExpenseId(
        localId: String,
        expenseId: String,
    )

    @Query("UPDATE captured_tx SET undoRequested = 1 WHERE localId = :localId")
    suspend fun requestUndo(localId: String)

    @Query("SELECT * FROM captured_tx WHERE undoRequested = 1")
    suspend fun undoRequests(): List<CapturedTxEntity>

    @Query("DELETE FROM captured_tx WHERE localId = :localId")
    suspend fun delete(localId: String)

    @Query("DELETE FROM captured_tx WHERE capturedAtMillis < :cutoff")
    suspend fun purgeBefore(cutoff: Long)

    @Query("SELECT * FROM captured_tx")
    suspend fun all(): List<CapturedTxEntity>

    /**
     * Records of ANOTHER channel with the same amount and direction received around the same time and not matched yet (one transfer
     * announced twice), the closest in time first. The phone's receive time is used, not the time written in the message body.
     */
    @Query(
        """SELECT * FROM captured_tx WHERE source != :source AND currency = :currency AND amountMinor = :amountMinor
            AND paired = 0 AND kind IN (:kinds) AND receivedAtMillis BETWEEN :from AND :to
            ORDER BY ABS(receivedAtMillis - :at), capturedAtMillis""",
    )
    suspend fun otherChannel(
        source: String,
        currency: String,
        amountMinor: Long,
        kinds: List<String>,
        at: Long,
        from: Long,
        to: Long,
    ): List<CapturedTxEntity>

    /** Marks a record as matched with [otherId]; 1 for the one caller that did it, 0 when someone else already had. */
    @Query("UPDATE captured_tx SET paired = 1, pairedLocalId = :otherId WHERE localId = :localId AND paired = 0")
    suspend fun claimPair(
        localId: String,
        otherId: String,
    ): Int

    /** Has this message (or its dropped / replaced twin) been handled already? */
    @Query("SELECT COUNT(*) FROM captured_tx WHERE localId = :id OR pairedLocalId = :id")
    suspend fun knows(id: String): Int

    /** Unmatched records that still wait in the upload outbox, oldest first. */
    @Query(
        """SELECT c.* FROM captured_tx c INNER JOIN parsed_outbox p ON p.localId = c.localId
            WHERE c.paired = 0 AND c.kind IS NOT NULL AND p.failed = 0 ORDER BY c.receivedAtMillis, c.capturedAtMillis""",
    )
    suspend fun queuedUnpaired(): List<CapturedTxEntity>
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
            CapturedTxEntity::class,
            MemberEntity::class,
            ReceiptEntity::class,
            ExpenseSourceEntity::class,
        ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao

    abstract fun outbox(): OutboxDao

    abstract fun lookups(): LookupDao

    abstract fun parsedOutbox(): ParsedOutboxDao

    abstract fun reviews(): ReviewDao

    abstract fun learnedTemplates(): LearnedTemplateDao

    abstract fun captured(): CapturedDao

    abstract fun sources(): ExpenseSourceDao

    abstract fun receipts(): ReceiptDao

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
        val pending = expenses().find(clientId)
        // No delete-and-reinsert of data: the server row is written first (local-only columns carried over), the pending row's
        // sources and receipts are re-pointed to it, and only then is the emptied pending row removed.
        expenses().upsert(if (pending != null) serverRow.keepLocalFrom(pending) else serverRow)
        if (pending != null && clientId != serverRow.id) {
            expenses().moveSources(clientId, serverRow.id)
            expenses().moveReceipts(clientId, serverRow.id)
            expenses().delete(clientId)
        }
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

private const val CREATE_CAPTURED_TX = """
CREATE TABLE IF NOT EXISTS captured_tx (
  localId TEXT NOT NULL,
  expenseId TEXT,
  amountMinor INTEGER NOT NULL,
  currency TEXT NOT NULL,
  label TEXT,
  source TEXT NOT NULL,
  templateId TEXT,
  capturedAtMillis INTEGER NOT NULL,
  PRIMARY KEY(localId)
)
"""

private const val MIGRATION_8_BACKFILL_KIND =
    "UPDATE captured_tx SET kind = (SELECT p.kind FROM parsed_outbox p WHERE p.localId = captured_tx.localId)"
private const val MIGRATION_8_BACKFILL_LAST4 =
    "UPDATE captured_tx SET instrumentLast4 = (SELECT p.instrumentLast4 FROM parsed_outbox p WHERE p.localId = captured_tx.localId)"

/** v7 -> v8: what a captured record was, so a second channel announcing the same transfer is recognized. */
val MIGRATION_7_8: Migration =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE captured_tx ADD COLUMN kind TEXT")
            db.execSQL("ALTER TABLE captured_tx ADD COLUMN receivedAtMillis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE captured_tx ADD COLUMN paired INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE captured_tx ADD COLUMN instrumentLast4 TEXT")
            db.execSQL("ALTER TABLE captured_tx ADD COLUMN pairedLocalId TEXT")
            db.execSQL("ALTER TABLE parsed_outbox ADD COLUMN claimedAtMillis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE parsed_outbox ADD COLUMN claims INTEGER NOT NULL DEFAULT 0")
            // a row that was already tried may have reached the server: it counts as claimed
            db.execSQL("UPDATE parsed_outbox SET claims = attempts")
            // Records still queued from before the update keep matching: kind and tail come from the outbox row, the capture time
            // stands in for the receive time. Already uploaded ones stay unmatched.
            db.execSQL(MIGRATION_8_BACKFILL_KIND)
            db.execSQL(MIGRATION_8_BACKFILL_LAST4)
            db.execSQL("UPDATE captured_tx SET receivedAtMillis = capturedAtMillis WHERE kind IS NOT NULL")
            db.execSQL("ALTER TABLE expense ADD COLUMN type TEXT NOT NULL DEFAULT 'EXPENSE'")
            db.execSQL("ALTER TABLE expense ADD COLUMN status TEXT NOT NULL DEFAULT 'CONFIRMED'")
        }
    }

/** v6 -> v7 (round 19): an Undo pressed while the upload was in flight is remembered until the expense id is known. */
val MIGRATION_6_7: Migration =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE captured_tx ADD COLUMN undoRequested INTEGER NOT NULL DEFAULT 0")
        }
    }

/** v5 -> v6 (round 18): the phone's own automatic records, for the "Recorded ... Undo" notification and the "Captured" chip. */
val MIGRATION_5_6: Migration =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_CAPTURED_TX)
        }
    }

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
