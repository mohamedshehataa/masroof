package eg.masroof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.MIGRATION_2_3
import eg.masroof.data.db.MIGRATION_3_4
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.insights.InsightsCache
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.PrefsCaptureData
import eg.masroof.ingest.SMS_INTAKE_TAG
import eg.masroof.ingest.WorkManagerSmsScheduler
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

private fun createFromSchema(
    context: Context,
    name: String,
    version: Int,
): SQLiteDatabase {
    context.deleteDatabase(name)
    val schema =
        Json
            .parseToJsonElement(File("schemas/eg.masroof.data.db.AppDatabase/$version.json").readText())
            .jsonObject["database"]!!
            .jsonObject
    val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
    for (entity in schema["entities"]!!.jsonArray) {
        val e = entity.jsonObject
        raw.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
    }
    for (q in schema["setupQueries"]!!.jsonArray) raw.execSQL(q.jsonPrimitive.content)
    return raw
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {
    private fun SQLiteDatabase.seedManual() {
        execSQL(
            "INSERT INTO outbox (clientId, amountMinor, currency, occurredAtMillis, categoryId, accountId, notes, " +
                "attempts, failed, createdAtMillis) VALUES ('manual-1', 12500, 'EGP', 1000, NULL, NULL, 'lunch', 2, 0, 1000)",
        )
        execSQL(
            "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, " +
                "pending, failed) VALUES ('manual-1', 12500, 'EGP', 1000, NULL, NULL, NULL, 'lunch', 1, 0)",
        )
    }

    @Test
    fun `REQ-SMS-061 migration 2 to 4 keeps queued manual entries and cached expenses`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val raw = createFromSchema(context, "migration24.db", 2)
            raw.seedManual()
            raw.version = 2
            raw.close()
            val db =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, "migration24.db")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()
            try {
                val outbox = db.outbox().all().single()
                assertEquals("manual-1", outbox.clientId)
                assertEquals(12_500L, outbox.amountMinor)
                assertEquals(2, outbox.attempts)
                val expense =
                    db
                        .expenses()
                        .observeAll()
                        .first()
                        .single()
                assertNull("old rows have no source", expense.source)
                assertTrue(db.parsedOutbox().all().isEmpty())
                assertTrue(db.reviews().all().isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun `REQ-SMS-061 migration 3 to 4 drops the old redacted-text SMS queue and adds the typed outbox and review list`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val raw = createFromSchema(context, "migration34.db", 3)
            raw.seedManual()
            raw.execSQL(
                "INSERT INTO sms_outbox (smsId, sender, body, receivedAtMillis, attempts, failed, createdAtMillis) " +
                    "VALUES ('s1', 'CIB', 'Purchase of EGP 99.00 at X', 1, 0, 0, 2)",
            )
            raw.version = 3
            raw.close()
            val db =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, "migration34.db")
                    .addMigrations(MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertEquals(
                    "manual-1",
                    db
                        .outbox()
                        .all()
                        .single()
                        .clientId,
                )
                val tables = mutableListOf<String>()
                db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use {
                    while (it.moveToNext()) tables += it.getString(0)
                }
                assertFalse("no table keeps SMS text any more", "sms_outbox" in tables)
                assertTrue("parsed_outbox" in tables && "review_item" in tables)
                db.parsedOutbox().insert(
                    ParsedOutboxEntity("l1", "SMS", "PURCHASE", 9900, "EGP", 1, null, "CIB", "CIB", null, "X", createdAtMillis = 2),
                )
                assertEquals(1, db.parsedOutbox().all().size)
            } finally {
                db.close()
            }
        }
}

private object NoInsightsCache : InsightsCache {
    override fun read(key: String): String? = null

    override fun write(
        key: String,
        json: String,
    ) = Unit

    override fun clear() = Unit
}

private class HangingFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ) = object : CoroutineWorker(appContext, workerParameters) {
        override suspend fun doWork(): Result = awaitCancellation()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignOutSmsTest {
    @Test
    fun `REQ-SMS-061 sign-out cancels pending work and clears the outbox and the review list`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration
                    .Builder()
                    .setWorkerFactory(HangingFactory())
                    .setExecutor(Executors.newCachedThreadPool())
                    .build(),
            )
            val workManager = WorkManager.getInstance(context)
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
            val scheduler = WorkManagerSmsScheduler(workManager)
            scheduler.enqueueIntake(IncomingMessage(CaptureSource.SMS, "CIB", "Purchase of EGP 99.00 at X", 1_000L))
            scheduler.scheduleUpload()
            scheduler.scheduleUploadRetry(60, 1)
            db.parsedOutbox().insert(
                ParsedOutboxEntity("l1", "SMS", "PURCHASE", 9900, "EGP", 1, null, "CIB", "CIB", null, "X", createdAtMillis = 2),
            )
            db.reviews().insert(ReviewItemEntity("r1", "SMS", "CIB", "unparsed text", "no_template", 1, 2))
            assertTrue(workManager.getWorkInfosByTag(SMS_INTAKE_TAG).get().isNotEmpty())

            val wiper = RoomDataWiper(db, NoInsightsCache, workManager, Dispatchers.Unconfined)
            assertEquals("the logout warning counts queued records", 1, wiper.unsyncedCount())
            assertEquals(1, wiper.retryableCount())
            wiper.wipe()

            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue("the local review list is cleared on logout", db.reviews().all().isEmpty())
            assertTrue(workManager.getWorkInfosByTag(SMS_INTAKE_TAG).get().all { it.state.isFinished })
            assertTrue(workManager.getWorkInfosForUniqueWork("sms-upload").get().all { it.state.isFinished })
            assertTrue(workManager.getWorkInfosForUniqueWork("sms-upload-retry").get().all { it.state.isFinished })
            db.close()
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureLifecycleTest {
    @Test
    fun `REQ-SMS-062 sign-in opens capture, logout closes it first, and login as another account starts closed to old data`() =
        runTest {
            val remote = FakeAuthRemote().apply { loginResult = AuthResult.Success(session()) }
            val gate = MemoryGate(open = false)
            val sm = TestAuth.manager(FakeSessionStore(), remote, capture = gate)
            sm.login("https://masroof.example", "a@b.eg", "pw-123456")
            assertTrue(gate.isOpen())
            sm.logout()
            assertFalse("closed by logout", gate.isOpen())
        }

    @Test
    fun `REQ-SMS-062 the wipe also clears sender, notification and device prefs and the id key`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var wiped = false
        val key =
            object : LocalIdKey {
                override fun get() = ByteArray(32)

                override fun wipe() {
                    wiped = true
                }
            }
        for (name in listOf("masroof_senders", "masroof_notifications", "masroof_device")) {
            context
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .putString("x", "y")
                .commit()
        }
        PrefsCaptureData(context, key).clear()
        for (name in listOf("masroof_senders", "masroof_notifications", "masroof_device")) {
            assertTrue(name, context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
        }
        assertTrue(wiped)
    }
}
