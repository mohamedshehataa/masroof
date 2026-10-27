package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.auth.AuthResult
import eg.masroof.data.auth.EraseCounts
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.LocalDataWiper
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.LearnedTemplateEntity
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.ingest.CaptureData
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.viewmodel.EraseState
import eg.masroof.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val TABLES =
    listOf("expense", "expense_source", "category", "account", "member", "review_item", "learned_template", "receipt")

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EraseAndSessionDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var receipts: File
    private lateinit var context: Context
    private val clock = Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        receipts = tmp.newFolder("receipts")
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun count(table: String): Long =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun counts() = TABLES.associateWith { count(it) }

    /** Local data of every kind, plus two receipt files. */
    private suspend fun fill() {
        DefaultSeed.seed(db.openHelper.writableDatabase)
        db.openHelper.writableDatabase.execSQL("INSERT INTO account (id, label, currency, archived) VALUES ('a1','CIB','EGP',0)")
        db.expenses().insert(ExpenseEntity("e1", 100, "EGP", 1, null, "cat-car.fuel", "a1", null, pending = false))
        db.expenses().insert(ExpenseEntity("e2", 200, "EGP", 2, null, null, null, null, pending = false))
        db.sources().insert(ExpenseSourceEntity("s1", "e1", "MANUAL", "e1", 1, null, null))
        db.reviews().insert(ReviewItemEntity("r1", "SMS", "CIB", "text", "no_template", 1, clock.millis()))
        db.learnedTemplates().upsert(LearnedTemplateEntity("t1", "CIB", "shape", "PURCHASE", -1, -1, 1))
        db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO receipt (id, expenseId, fileName, mimeType, sizeBytes, sha256, pageCount, createdAtMillis)
            VALUES ('rc1','e1','f.jpg','image/jpeg',1,'h1',NULL,1)
            """.trimIndent(),
        )
        File(receipts, "f.jpg").writeText("img")
        File(receipts, "g.pdf").writeText("pdf")
    }

    private fun sessionManager(
        remote: FakeAuthRemote = FakeAuthRemote(),
        store: FakeSessionStore = FakeSessionStore(LoadedSession.Found(session())),
    ) = SessionManager(store, remote, FakeSettings(), Dispatchers.Unconfined)

    // ---- REQ-AND-131

    @Test
    fun `REQ-AND-131 logout, a rejected refresh, a different member and a lost keystore key leave every table and file as they were`() =
        runTest {
            fill()
            val before = counts()
            assertTrue(before.values.all { it > 0 })

            sessionManager().logout()
            assertEquals(before, counts())

            val expiring = FakeAuthRemote().apply { refreshResult = { AuthResult.Rejected } }
            sessionManager(expiring).refreshAfter("access-1")
            assertEquals(before, counts())

            val other = FakeSessionStore().apply { lastMemberId = "someone-else" }
            val login = FakeAuthRemote().apply { loginResult = AuthResult.Success(session()) }
            sessionManager(login, other).login("https://x.eg", "a@b.eg", "pw")
            assertEquals(before, counts())

            sessionManager(store = FakeSessionStore(LoadedSession.Invalidated)).restore()
            assertEquals(before, counts())

            assertEquals(setOf("f.jpg", "g.pdf"), receipts.list()!!.toSet())
        }

    @Test
    fun `REQ-AND-131 the session manager has no way to reach the database or the wiper`() {
        val params = SessionManager::class.java.constructors.flatMap { it.parameterTypes.toList() }
        assertTrue(params.none { AppDatabase::class.java.isAssignableFrom(it) || LocalDataWiper::class.java.isAssignableFrom(it) })
    }

    // ---- REQ-AND-132 (the wiper)

    private class Probe : CapturedNotifier, CaptureData {
        var notificationsCancelled = 0
        var captureCleared = 0

        override fun captured(item: CapturedTxEntity) = Unit

        override fun cancel(localId: String) = Unit

        override fun cancelAll() {
            notificationsCancelled++
        }

        override fun clear() {
            captureCleared++
        }
    }

    private fun wiper(
        probe: Probe,
        gate: MemoryGate,
    ) = RoomDataWiper(
        db,
        NoInsightsCache,
        androidx.work.WorkManager.getInstance(context),
        Dispatchers.Unconfined,
        probe,
        probe,
        gate,
        receipts,
    )

    @Test
    fun `REQ-AND-132 erase clears every table and the receipts folder, seeds the defaults and gives a fresh install`() =
        runTest {
            fill()
            db.parsedOutbox().insert(
                ParsedOutboxEntity("l1", "SMS", "PURCHASE", 9_900, "EGP", 1, null, "CIB", "CIB", null, "X", createdAtMillis = 2),
            )
            db.captured().insert(CapturedTxEntity("c1", "e-1", 50_000, "EGP", "AMAZON", "SMS", null, 1))
            val probe = Probe()
            val gate = MemoryGate(open = true)
            val wiper = wiper(probe, gate)

            assertEquals(EraseCounts(entries = 2, receipts = 1), wiper.counts())
            wiper.wipe()

            assertEquals(0L, count("expense"))
            assertEquals(0L, count("expense_source"))
            assertEquals(0L, count("receipt"))
            assertEquals(0L, count("review_item"))
            assertEquals(0L, count("learned_template"))
            assertEquals(0L, count("account"))
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
            assertEquals("the 29 default categories are back", 29L, count("category"))
            assertEquals("and the member Me", 1L, count("member"))
            assertTrue(receipts.list()!!.isEmpty())
            assertEquals(1, probe.captureCleared)
            assertEquals("every Recorded ... Undo notification is cancelled", 1, probe.notificationsCancelled)
            assertTrue("capture is open again", gate.isOpen())
        }

    @Test
    fun `REQ-AND-132 the erase-in-progress flag is closed while the erase runs and reopened at the end`() =
        runTest {
            fill()
            val seen = mutableListOf<Boolean>()
            val gate = MemoryGate(open = true)
            val probe =
                object : CaptureData {
                    override fun clear() {
                        seen += gate.isOpen()
                    }
                }
            RoomDataWiper(
                db,
                NoInsightsCache,
                androidx.work.WorkManager.getInstance(context),
                Dispatchers.Unconfined,
                probe,
                eg.masroof.ingest.NoCapturedNotifier,
                gate,
                receipts,
            ).wipe()
            assertEquals(listOf(false), seen)
            assertTrue(gate.isOpen())
        }

    @Test
    fun `REQ-AND-132 a capture that was in flight when the erase started writes nothing`() =
        runTest {
            val gate = MemoryGate(open = true)
            // the erase starts after the job began: the key fetch is the point where the flag gets closed
            val key =
                object : eg.masroof.ingest.LocalIdKey {
                    override fun get(): ByteArray {
                        gate.close()
                        return ByteArray(32)
                    }

                    override fun wipe() = Unit
                }
            val pipeline =
                SmsPipeline(db, MemorySettings(), MemoryNotifications(), FakeSmsScheduler(), clock, gate, key)
            val message =
                IncomingMessage(
                    CaptureSource.SMS,
                    "CIB",
                    "Your card ending 4512 was charged EGP 99.00 at PHARMACY on 12/03/2026 14:05",
                    1_000L,
                )
            assertEquals(Disposition.DROPPED, pipeline.accept(message))
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue(db.reviews().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
            // control: with the flag open the same message is kept
            gate.open()
            val fresh = SmsPipeline(db, MemorySettings(), MemoryNotifications(), FakeSmsScheduler(), clock, gate, FixedKey)
            assertEquals(Disposition.QUEUED, fresh.accept(message))
        }

    // ---- REQ-AND-132 (the Settings flow)

    private fun settingsVm(wiper: FakeWiper) = SettingsViewModel(FakeSettings(), TestAuth.manager(settings = FakeSettings()), wiper)

    @Test
    fun `REQ-AND-132 the dialog shows the counts and nothing is deleted until the second confirmation`() =
        runTest {
            val wiper = FakeWiper(entries = 12, receipts = 3)
            val vm = settingsVm(wiper)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.askErase()
            assertEquals(EraseState.Confirm(EraseCounts(12, 3)), vm.state.value.erase)
            assertEquals(0, wiper.wipes)
            vm.confirmErase()
            assertEquals(1, wiper.wipes)
            assertEquals(EraseState.Idle, vm.state.value.erase)
        }

    @Test
    fun `REQ-AND-132 Cancel or Back in the dialog deletes nothing`() =
        runTest {
            val wiper = FakeWiper(entries = 12, receipts = 3)
            val vm = settingsVm(wiper)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.askErase()
            vm.dismissErase() // Cancel and Back both call onDismissRequest
            assertEquals(EraseState.Idle, vm.state.value.erase)
            vm.confirmErase() // a stale tap without the dialog does nothing
            assertEquals(0, wiper.wipes)
        }

    @Test
    fun `REQ-AND-132 an erase that rolled back shows Couldn't erase and can be tried again`() =
        runTest {
            val wiper = FakeWiper(entries = 1).apply { failWith = IllegalStateException("db") }
            val vm = settingsVm(wiper)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.askErase()
            vm.confirmErase()
            assertEquals(EraseState.Failed, vm.state.value.erase)
            assertFalse(wiper.wipes > 0)
            wiper.failWith = null
            vm.askErase()
            vm.confirmErase()
            assertEquals(1, wiper.wipes)
        }

    @Test
    fun `REQ-AND-130 the app opens on Home and MainActivity holds no session`() {
        assertEquals("home", eg.masroof.START_ROUTE)
        val fields = MainActivity::class.java.declaredFields.map { it.type }
        assertTrue(fields.none { SessionManager::class.java.isAssignableFrom(it) })
    }
}
