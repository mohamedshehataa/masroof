package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.backup.BackupStage
import eg.masroof.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupEraseSweepTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var noBackup: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(context)
        noBackup =
            File(context.cacheDir, "no-backup-test").also {
                it.deleteRecursively()
                it.mkdirs()
            }
    }

    @After
    fun tearDown() {
        db.close()
        noBackup.deleteRecursively()
    }

    @Test
    fun `REQ-SEC-066 Erase all data removes staged backup rows a killed backup left behind`() {
        val stage = BackupStage.dir(noBackup)
        stage.mkdirs()
        File(stage, "masroof-stage-leftover.bin").writeBytes(byteArrayOf(1, 2, 3))
        val wiper =
            RoomDataWiper(
                db,
                NoInsightsCache,
                androidx.work.WorkManager.getInstance(context),
                Dispatchers.Unconfined,
                safetyCopyDir = noBackup,
            )
        runBlocking { wiper.wipe() }
        assertEquals(0, stage.listFiles()?.size ?: 0)
        assertTrue(noBackup.exists())
    }
}
