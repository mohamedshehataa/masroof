package eg.masroof.backup

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import eg.masroof.data.backup.BackupEnvironmentFactory
import eg.masroof.data.backup.BackupFailure
import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.BackupInput
import eg.masroof.data.backup.BackupStatusStore
import eg.masroof.data.backup.BackupWorker
import eg.masroof.data.backup.LastBackup
import eg.masroof.data.backup.PassphraseKdf
import eg.masroof.data.backup.PendingBackupKey
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.LocalIdKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWorkerTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var status: BackupStatusStore
    private lateinit var pending: PendingBackupKey
    private lateinit var receipts: File
    private val clock = Clock.fixed(Instant.parse("2026-10-27T11:05:00Z"), ZoneOffset.UTC)
    private val uri: Uri = Uri.parse("content://docs.example/document/masroof")
    private val localKey = ByteArray(32) { 3 }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        status =
            BackupStatusStore(context.getSharedPreferences("backup-worker-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        pending = PendingBackupKey()
        receipts = File(context.cacheDir, "worker-receipts").also { it.deleteRecursively() }
        receipts.mkdirs()
        db.openHelper.writableDatabase.execSQL("INSERT INTO member (id, displayName, sortOrder) VALUES ('m1', 'Me', 0)")
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO expense (id, amountMinor, currency, occurredAtMillis, pending, failed) VALUES ('e1', 12500, 'EGP', 1793000000000, 0, 0)",
        )
    }

    @After
    fun tearDown() {
        db.close()
        receipts.deleteRecursively()
    }

    private fun worker(): BackupWorker {
        val factory =
            BackupEnvironmentFactory(
                db,
                context,
                receipts,
                object : LocalIdKey {
                    override fun get() = localKey.copyOf()

                    override fun wipe() = Unit
                },
            )
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(workDataOf(BackupWorker.KEY_URI to uri.toString()))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = BackupWorker(appContext, workerParameters, factory, pending, status, clock)
                },
            ).build()
    }

    private fun input(): BackupInput {
        val header = BackupHeader(1, 1, 64, 1, 1, ByteArray(16) { 4 }, 1, 0)
        return BackupInput(header, PassphraseKdf.deriveKey("correct horse battery", header))
    }

    private fun counts() =
        listOf("expense", "member").map { t ->
            db.query("SELECT COUNT(*) FROM $t", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        }

    @Test
    fun `REQ-OPS-177 the task writes the file the user picked, records the last backup and reports the counts`() {
        val sink = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, sink)
        pending.put(input())
        val result = runBlocking { worker().doWork() }
        assertTrue(result is ListenableWorker.Result.Success)
        val out = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1L, out.getLong(BackupWorker.KEY_ENTRIES, -1))
        assertEquals(0, out.getInt(BackupWorker.KEY_RECEIPTS, -1))
        assertEquals(sink.size().toLong(), out.getLong(BackupWorker.KEY_BYTES, -1))
        assertEquals(LastBackup(clock.millis(), 1, 0, sink.size().toLong()), status.last.value)
        val plain = BackupTestDecryptor.decrypt(sink.toByteArray(), "correct horse battery")
        assertEquals("manifest.json", plain.names.first())
        assertNull("the key is used once and is gone", pending.take())
    }

    @Test
    fun `REQ-OPS-177 a provider failure mid-write fails the task with a reason, keeps the last backup line and the app data`() {
        status.record(LastBackup(1_700_000_000_000L, 5, 1, 1024))
        val before = status.last.value
        val rowsBefore = counts()
        val failing =
            object : OutputStream() {
                var n = 0

                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    n += len
                    if (n > 100) throw IOException("write failed: ENOSPC (No space left on device)")
                }
            }
        shadowOf(context.contentResolver).registerOutputStream(uri, failing)
        pending.put(input())
        val result = runBlocking { worker().doWork() }
        assertTrue(result is ListenableWorker.Result.Failure)
        val reason = (result as ListenableWorker.Result.Failure).outputData.getString(BackupWorker.KEY_REASON)
        assertEquals(BackupFailure.STORAGE_FULL.name, reason)
        assertEquals("the last-backup line is not updated", before, status.last.value)
        assertEquals("the app's data is unchanged", rowsBefore, counts())
    }

    @Test
    fun `REQ-OPS-177 a location that cannot be opened fails with a reason and writes nothing`() {
        pending.put(input())
        val result = runBlocking { worker().doWork() }
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            BackupFailure.CANNOT_OPEN.name,
            (result as ListenableWorker.Result.Failure).outputData.getString(BackupWorker.KEY_REASON),
        )
        assertNull(status.last.value)
    }

    @Test
    fun `REQ-OPS-177 without a key (the process was restarted) the task fails and never writes a partial file`() {
        val sink = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, sink)
        val result = runBlocking { worker().doWork() }
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(BackupFailure.KEY_LOST.name, (result as ListenableWorker.Result.Failure).outputData.getString(BackupWorker.KEY_REASON))
        assertEquals(0, sink.size())
        assertNull(status.last.value)
    }

    @Test
    fun `REQ-OPS-177 the task is a dataSync foreground task with a progress notification and a Cancel action and no private content`() {
        val info = runBlocking { worker().getForegroundInfo() }
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        val n = info.notification
        assertEquals("Backing up Masroof", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(1, n.actions.size)
        assertEquals("Cancel", n.actions[0].title.toString())
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse("ongoing: it cannot be swiped away while it runs", n.flags and Notification.FLAG_ONGOING_EVENT == 0)
    }
}
