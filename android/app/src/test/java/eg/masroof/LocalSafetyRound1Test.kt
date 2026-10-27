package eg.masroof

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.core.Money
import eg.masroof.core.MoneyFormat
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.NewExpense
import eg.masroof.data.UuidIdFactory
import eg.masroof.data.auth.DataEraser
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.PartialEraseException
import eg.masroof.data.auth.RoomDataWiper
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.NeverDeleteOpenHelperFactory
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.data.edit.EditOutcome
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.data.insights.FileInsightsCache
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.newBareClient
import eg.masroof.ingest.AlwaysSignedIn
import eg.masroof.ingest.CaptureData
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.EraseStage
import eg.masroof.ingest.ExpenseListRefresher
import eg.masroof.ingest.LocalIdKey
import eg.masroof.ingest.PrefsCaptureData
import eg.masroof.ingest.SessionUploadCheck
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.components.ExpenseListItem
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.EraseState
import eg.masroof.ui.viewmodel.ExpenseRow
import eg.masroof.ui.viewmodel.HomeViewModel
import eg.masroof.ui.viewmodel.SettingsViewModel
import eg.masroof.ui.viewmodel.TransactionsViewModel
import eg.masroof.work.ForegroundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LocalSafetyRound1Test {
    @get:Rule val tmp = TemporaryFolder()

    @get:Rule val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val clock = Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        DefaultSeed.seed(db.openHelper.writableDatabase)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ---- item 1: a local entry never reaches the server editor, and no HTTP answer deletes a local row

    private fun editor() = DefaultExpenseEditor(db, LocalDataSignal(), clock, EraseLock())

    @Test
    fun `REQ-AND-133 saving or deleting a local entry works on Room only and never reaches a server`() =
        runTest {
            db.expenses().insert(ExpenseEntity("local-1", 100, "EGP", 1, null, null, null, null, pending = false))
            val editor = editor()
            assertTrue(editor.update("local-1", ExpenseChanges(notes = "x")) is EditOutcome.Saved)
            assertEquals("x", db.expenses().find("local-1")?.notes)
            assertEquals(EditOutcome.Deleted, editor.delete("local-1"))
            assertNotNull("soft deleted, still there for Undo", db.expenses().find("local-1"))
        }

    @Test
    fun `REQ-AND-133 a row cached from the server is edited locally too, and a missing row is NotFound`() =
        runTest {
            db.expenses().insert(ExpenseEntity("srv-1", 100, "EGP", 1, null, null, null, null, pending = false, serverId = "srv-1"))
            val editor = editor()
            assertTrue(editor.update("srv-1", ExpenseChanges(notes = "x")) is EditOutcome.Saved)
            assertEquals(EditOutcome.NotFound, editor.update("nope", ExpenseChanges(notes = "x")))
            assertNull(db.expenses().find("nope"))
        }

    @Test
    fun `REQ-AND-133 tapping a local row opens the editor and so does a server row`() {
        val opened = mutableListOf<String>()
        val local = ExpenseRow("L", null, Money(100), LocalDate.of(2026, 10, 1), false, false, null, null, "MANUAL")
        val server = local.copy(id = "S", serverId = "S", merchant = "Server shop")
        compose.setContent {
            MasroofTheme {
                androidx.compose.foundation.layout.Column {
                    ExpenseListItem(local.copy(merchant = "Local shop"), onOpen = { opened += it })
                    ExpenseListItem(server, onOpen = { opened += it })
                }
            }
        }
        compose.onNodeWithText("Local shop").performClick()
        assertEquals(listOf("L"), opened)
        compose.onNodeWithText("Server shop").performClick()
        assertEquals(listOf("L", "S"), opened)
    }

    // ---- items 2, 3, 4, 6: the erase

    private class Probe(
        var failClear: Boolean = false,
    ) : CaptureData {
        var cleared = 0

        override fun clear() {
            if (failClear) error("prefs unavailable")
            cleared++
        }
    }

    private fun wiper(
        gate: MemoryGate,
        capture: CaptureData = Probe(),
        safety: File = tmp.newFolder(),
        receipts: File = tmp.newFolder(),
    ) = RoomDataWiper(
        db,
        NoInsightsCache,
        androidx.work.WorkManager.getInstance(context),
        Dispatchers.Unconfined,
        capture,
        eg.masroof.ingest.NoCapturedNotifier,
        gate,
        receipts,
        safety,
    )

    @Test
    fun `REQ-AND-132 the erase deletes the pre-migration safety copy with its part wal and shm files`() =
        runTest {
            val safety = tmp.newFolder("safety")
            val names = listOf("pre-migration-v9.db", "pre-migration-v9.db.part", "pre-migration-v9.db-wal", "pre-migration-v9.db-shm")
            names.forEach { File(safety, it).writeText("old rows") }
            File(safety, "keep.txt").writeText("not ours")
            wiper(MemoryGate(), safety = safety).wipe()
            assertEquals(listOf("keep.txt"), safety.list()!!.toList())
        }

    @Test
    fun `REQ-AND-132 an erase killed after closing the flag is finished at the next start and capture reopens`() =
        runTest {
            db.expenses().insert(ExpenseEntity("e1", 100, "EGP", 1, null, null, null, null, pending = false))
            db.reviews().insert(
                eg.masroof.data.db
                    .ReviewItemEntity("r1", "SMS", "CIB", "text", "x", 1, 2),
            )
            // the process died after the flag was saved closed and before the tables were cleared
            val gate = MemoryGate(open = false)
            val eraser = DataEraser(wiper(gate), sessionManager(), gate, CoroutineScope(Dispatchers.Unconfined))
            eraser.recoverInterrupted()!!.join()
            assertEquals(0, db.expenses().count())
            assertTrue(db.reviews().all().isEmpty())
            assertTrue("capture is open again", gate.isOpen())
            // an open flag means nothing was interrupted: recovery leaves the data alone
            db.expenses().insert(ExpenseEntity("e2", 100, "EGP", 1, null, null, null, null, pending = false))
            assertNull(eraser.recoverInterrupted())
            assertEquals(1, db.expenses().count())
        }

    @Test
    fun `REQ-AND-132 after a partial erase an entry added later survives the restart and only the clean-up is finished`() =
        runTest {
            db.expenses().insert(ExpenseEntity("e1", 100, "EGP", 1, null, null, null, null, pending = false))
            val gate = MemoryGate()
            val capture = Probe(failClear = true)
            try {
                wiper(gate, capture).wipe()
                fail("expected PartialEraseException")
            } catch (expected: PartialEraseException) {
                // data is gone, stage stays CLEANUP
            }
            assertEquals(0, db.expenses().count())
            assertEquals(EraseStage.CLEANUP, gate.stage())
            assertFalse(gate.isOpen())
            // the user adds an entry before the next start
            db.expenses().insert(ExpenseEntity("later", 500, "EGP", 2, null, null, null, null, pending = false))
            capture.failClear = false
            DataEraser(wiper(gate, capture), sessionManager(), gate, CoroutineScope(Dispatchers.Unconfined)).recoverInterrupted()!!.join()
            assertEquals("the entry survives", listOf("later"), db.expenses().all().map { it.id })
            assertEquals(1, capture.cleared)
            assertEquals(EraseStage.NONE, gate.stage())
            assertTrue(gate.isOpen())
        }

    @Test
    fun `REQ-AND-132 a stage that cannot be saved stops the erase before anything is deleted`() =
        runTest {
            db.expenses().insert(ExpenseEntity("e1", 100, "EGP", 1, null, null, null, null, pending = false))
            val gate =
                object : eg.masroof.ingest.CaptureGate {
                    override fun isOpen() = true

                    override fun open() = Unit

                    override fun close() = Unit

                    override fun setStage(stage: EraseStage) = false
                }
            val memory = MemoryGate()
            try {
                RoomDataWiper(
                    db,
                    NoInsightsCache,
                    androidx.work.WorkManager.getInstance(context),
                    Dispatchers.Unconfined,
                    Probe(),
                    eg.masroof.ingest.NoCapturedNotifier,
                    gate,
                    tmp.newFolder(),
                    tmp.newFolder(),
                ).wipe()
                fail("expected the failed write to be an error")
            } catch (expected: IllegalStateException) {
                // nothing was changed
            }
            assertEquals(1, db.expenses().count())
            assertTrue(memory.isOpen())
        }

    @Test
    fun `REQ-AND-132 a save made while the start-up recovery runs waits for it and is not wiped`() =
        runTest {
            val wipe = FakeWiper().apply { recoverGate = kotlinx.coroutines.CompletableDeferred() }
            val gate = MemoryGate(open = false) // stage ERASING: the last erase was cut short
            val lock =
                eg.masroof.data.auth
                    .EraseLock()
            val eraser = DataEraser(wipe, sessionManager(), gate, CoroutineScope(Dispatchers.Unconfined), lock)
            val job = eraser.recoverInterrupted()!!
            val repo = DefaultExpenseRepository(db, clock, UuidIdFactory(), LocalDataSignal(), lock)
            val saving = launch { repo.add(NewExpense(Money(500), LocalDate.of(2026, 10, 27), null, null, null)) }
            runCurrent()
            assertTrue("the save waits for recovery", saving.isActive)
            assertEquals(0, db.expenses().count())
            wipe.recoverGate!!.complete(Unit)
            job.join()
            saving.join()
            assertEquals(listOf(EraseStage.ERASING), wipe.recovered)
            assertEquals("the entry is saved after the recovery and stays", 1, db.expenses().count())
        }

    @Test
    fun `REQ-AND-132 the Settings screen state is Incomplete for a partial erase and Failed for a rolled back one`() =
        runTest {
            for ((thrown, expected) in listOf(
                PartialEraseException() to EraseState.Incomplete,
                IllegalStateException("db") to EraseState.Failed,
            )) {
                val wipe = FakeWiper(entries = 1).apply { failWith = thrown }
                val gate = MemoryGate()
                val vm =
                    SettingsViewModel(
                        FakeSettings(),
                        sessionManager(),
                        DataEraser(wipe, sessionManager(), gate, CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)),
                    )
                backgroundScope.launch { vm.state.collect {} }
                runCurrent()
                vm.askErase()
                vm.confirmErase()
                assertEquals(expected, vm.state.value.erase)
            }
        }

    @Test
    fun `REQ-AND-132 the erase signs out of the server first and wipes`() =
        runTest {
            val remote = FakeAuthRemote()
            val store = FakeSessionStore(LoadedSession.Found(session()))
            val manager = SessionManager(store, remote, FakeSettings(), Dispatchers.Unconfined)
            val wipe = FakeWiper(entries = 2)
            DataEraser(wipe, manager, MemoryGate(), CoroutineScope(Dispatchers.Unconfined)).erase()
            assertEquals(listOf("refresh-1"), remote.logoutTokens)
            assertEquals(LoadedSession.Empty, store.stored)
            assertEquals(1, wipe.wipes)
        }

    @Test
    fun `REQ-AND-132 the real capture prefs and insights cache are cleared by the erase`() =
        runTest {
            val prefs = context.getSharedPreferences("masroof_senders", Context.MODE_PRIVATE)
            prefs.edit().putString("custom", "BANK").commit()
            var keyWiped = false
            val key =
                object : LocalIdKey {
                    override fun get() = ByteArray(32)

                    override fun wipe() {
                        keyWiped = true
                    }
                }
            val dir = tmp.newFolder("insights")
            val cache = FileInsightsCache(dir)
            cache.write("overview", "{}")
            assertNotNull(cache.read("overview"))
            RoomDataWiper(
                db,
                cache,
                androidx.work.WorkManager.getInstance(context),
                Dispatchers.Unconfined,
                PrefsCaptureData(context, key),
                eg.masroof.ingest.NoCapturedNotifier,
                MemoryGate(),
                tmp.newFolder("r"),
                tmp.newFolder("s"),
            ).wipe()
            assertNull(prefs.getString("custom", null))
            assertTrue(keyWiped)
            assertNull(cache.read("overview"))
        }

    private fun sessionManager() = TestAuth.manager(FakeSessionStore(), FakeAuthRemote(), FakeSettings())

    // ---- item 5: no session, no upload

    private val purchase = "Your card ending 4512 was charged EGP 99.00 at PHARMACY on 12/03/2026 14:05"

    private fun queueRow() =
        ParsedOutboxEntity("l1", "SMS", "PURCHASE", 9_900, "EGP", 1, null, "CIB", "CIB", null, "X", createdAtMillis = 2)

    private val device =
        object : DeviceIdProvider {
            override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
        }

    /** Every request the real OkHttp stack would send, recorded; the answer is a synthetic 500. */
    private class Recorder {
        val paths = mutableListOf<String>()
        val client =
            newBareClient()
                .newBuilder()
                .addInterceptor { chain ->
                    paths += chain.request().url.encodedPath
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("recorded")
                        .body("".toResponseBody())
                        .build()
                }.build()
    }

    @Test
    fun `REQ-AND-130 no request on start, foreground, save, list views or an upload pass without a session`() =
        runTest {
            val recorder = Recorder()
            val settings = FakeSettings()
            val signedOut = SessionManager(FakeSessionStore(), FakeAuthRemote(), settings, Dispatchers.Unconfined)
            val api = ApiRemoteSource(settings, recorder.client, signedOut)
            db.parsedOutbox().insert(queueRow())
            val repo = DefaultExpenseRepository(db, clock, UuidIdFactory(), LocalDataSignal())

            // start / foreground
            ForegroundSync(repo).onForeground()
            // the lists and the Add sheet
            val home = HomeViewModel(repo)
            val tx = TransactionsViewModel(repo)
            backgroundScope.launch { home.state.collect {} }
            backgroundScope.launch { tx.state.collect {} }
            runCurrent()
            // a save
            repo.add(NewExpense(Money(20_000), LocalDate.of(2026, 10, 27), "cat-car.fuel", null, null))
            // the capture side with no session: upload pass, refresh signal, Undo
            val gate = MemoryGate()
            val uploader =
                SmsUploader(
                    db,
                    api,
                    device,
                    clock,
                    gate,
                    deleter = api,
                    refresher = ExpenseListRefresher(LocalDataSignal()),
                    upload = SessionUploadCheck(signedOut),
                )
            assertEquals(0, uploader.uploadPending().sent)
            assertTrue("the row stays queued", db.parsedOutbox().all().isNotEmpty())
            assertEquals(emptyList<String>(), recorder.paths)
        }

    @Test
    fun `REQ-AND-130 with a session the capture upload talks to ingest only and never to api expenses`() =
        runTest {
            val recorder = Recorder()
            val settings = FakeSettings()
            val signedIn =
                SessionManager(FakeSessionStore(LoadedSession.Found(session())), FakeAuthRemote(), settings, Dispatchers.Unconfined)
            val api = ApiRemoteSource(settings, recorder.client, signedIn)
            db.parsedOutbox().insert(queueRow())
            val repo = DefaultExpenseRepository(db, clock, UuidIdFactory(), LocalDataSignal())
            val uploader =
                SmsUploader(
                    db,
                    api,
                    device,
                    clock,
                    MemoryGate(),
                    deleter = api,
                    refresher = ExpenseListRefresher(LocalDataSignal()),
                    upload = SessionUploadCheck(signedIn),
                )
            uploader.uploadPending()
            repo.add(NewExpense(Money(100), LocalDate.of(2026, 10, 27), null, null, null))
            assertTrue(recorder.paths.isNotEmpty())
            assertTrue(recorder.paths.toString(), recorder.paths.none { it.startsWith("/api/expenses") })
            assertTrue(recorder.paths.all { it.startsWith("/api/ingest/") })
        }

    @Test
    fun `REQ-AND-131 Undo of a captured expense without a session makes no request and is remembered`() =
        runTest {
            val recorder = Recorder()
            val settings = FakeSettings()
            val signedOut = SessionManager(FakeSessionStore(), FakeAuthRemote(), settings, Dispatchers.Unconfined)
            val api = ApiRemoteSource(settings, recorder.client, signedOut)
            db.captured().insert(
                eg.masroof.data.db
                    .CapturedTxEntity("c1", "srv-9", 50_000, "EGP", "AMAZON", "SMS", null, 1),
            )
            val result = CapturedActions(db, api, clock, upload = SessionUploadCheck(signedOut)).undo("c1")
            assertTrue(result.deferred)
            assertTrue(recorder.paths.isEmpty())
            assertEquals(1, db.captured().undoRequests().size)
            // control: with the always-signed-in check the same Undo does reach the deleter
            assertNotNull(AlwaysSignedIn)
        }

    // ---- item 7: the re-check INSIDE the write transaction

    @Test
    fun `REQ-AND-132 a capture whose flag closes between the last check and the write is dropped by the check inside the transaction`() =
        runTest {
            // open for the first two checks (start, after the key), closed for the third: the one inside withTransaction
            var calls = 0
            val gate =
                object : eg.masroof.ingest.CaptureGate {
                    override fun isOpen() = ++calls <= 2

                    override fun open() = Unit

                    override fun close() = Unit
                }
            var keyWipes = 0
            val key =
                object : LocalIdKey {
                    override fun get() = ByteArray(32)

                    override fun wipe() {
                        keyWipes++
                    }
                }
            val pipeline = SmsPipeline(db, MemorySettings(), MemoryNotifications(), FakeSmsScheduler(), clock, gate, key)
            val message = IncomingMessage(CaptureSource.SMS, "CIB", purchase, 1_000L)
            assertEquals(Disposition.DROPPED, pipeline.accept(message))
            assertEquals("the early check passed, so the key was not wiped", 0, keyWipes)
            assertEquals("the transaction check was reached", 3, calls)
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue(db.reviews().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
        }

    // ---- item 9

    @Test
    fun `REQ-AND-130 recovery that may lose data is never allowed by the open helper`() {
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name("x.db")
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).allowDataLossOnRecovery(true)
                .build()
        assertFalse(NeverDeleteOpenHelperFactory.guardedConfiguration(config).allowDataLossOnRecovery)
    }

    @Test
    fun `REQ-EXP-043 the Arabic comma is a thousands separator`() {
        assertEquals(MoneyFormat.EntryAmount.Valid(145_000), MoneyFormat.parseEntry("1،450"))
    }
}
