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
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.MIGRATION_2_3
import eg.masroof.data.db.SmsOutboxEntity
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.SMS_INTAKE_TAG
import eg.masroof.ingest.WorkManagerSmsScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMigrationTest {
    @Test
    fun `REQ-SMS-061 migration 2 to 3 keeps queued manual entries and cached expenses`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase("migration.db")
            // Build a real version-2 database from the exported v2 schema, with rows in it.
            val v2 =
                Json
                    .parseToJsonElement(File("schemas/eg.masroof.data.db.AppDatabase/2.json").readText())
                    .jsonObject["database"]!!
                    .jsonObject
            val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("migration.db"), null)
            for (entity in v2["entities"]!!.jsonArray) {
                val e = entity.jsonObject
                raw.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
            }
            for (q in v2["setupQueries"]!!.jsonArray) raw.execSQL(q.jsonPrimitive.content)
            raw.execSQL(
                "INSERT INTO outbox (clientId, amountMinor, currency, occurredAtMillis, categoryId, accountId, notes, " +
                    "attempts, failed, createdAtMillis) VALUES ('manual-1', 12500, 'EGP', 1000, NULL, NULL, 'lunch', 2, 0, 1000)",
            )
            raw.execSQL(
                "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, merchant, categoryId, accountId, notes, " +
                    "pending, failed) VALUES ('manual-1', 12500, 'EGP', 1000, NULL, NULL, NULL, 'lunch', 1, 0)",
            )
            raw.version = 2
            raw.close()

            // Room validates the migrated schema against the v3 entities when it opens the file.
            val db =
                Room
                    .databaseBuilder(context, AppDatabase::class.java, "migration.db")
                    .addMigrations(MIGRATION_2_3)
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
                assertEquals("manual-1", expense.id)
                assertNull("old rows have no source", expense.source)
                db.smsOutbox().insert(SmsOutboxEntity("s1", "CIB", "body", 1L, createdAtMillis = 2L))
                assertEquals(1, db.smsOutbox().all().size)
            } finally {
                db.close()
            }
        }
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
    fun `REQ-SMS-061 sign-out cancels pending SMS work and clears the SMS queue`() =
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
            scheduler.enqueueIntake(IncomingSms("CIB", "Purchase of EGP 99.00 at X", 1_000L))
            scheduler.scheduleUpload()
            scheduler.scheduleUploadRetry(60, 1)
            db.smsOutbox().insert(SmsOutboxEntity("s1", "CIB", "body", 1L, createdAtMillis = 2L))
            assertTrue(workManager.getWorkInfosByTag(SMS_INTAKE_TAG).get().isNotEmpty())

            RoomDataWiper(db, workManager, Dispatchers.Unconfined).wipe()

            assertTrue(db.smsOutbox().all().isEmpty())
            assertTrue(workManager.getWorkInfosByTag(SMS_INTAKE_TAG).get().all { it.state.isFinished })
            assertTrue(workManager.getWorkInfosForUniqueWork("sms-upload").get().all { it.state.isFinished })
            assertTrue(workManager.getWorkInfosForUniqueWork("sms-upload-retry").get().all { it.state.isFinished })
            db.close()
        }
}
