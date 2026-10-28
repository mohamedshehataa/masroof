package eg.masroof

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.db.AppDatabase
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.IncomingSms
import eg.masroof.ingest.PrefsNotificationSettings
import eg.masroof.ingest.PrefsSenderSettings
import eg.masroof.ingest.SMS_INTAKE_TAG
import eg.masroof.ingest.SMS_UPLOAD_RETRY_WORK
import eg.masroof.ingest.SMS_UPLOAD_WORK
import eg.masroof.ingest.SenderSettings
import eg.masroof.ingest.SmsHandoff
import eg.masroof.ingest.SmsIntakeWorker
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.WorkManagerSmsScheduler
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.settings.SmsCaptureSwitch
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.SmsCaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A settings fake that says "on" for the first [onFor] questions and "off" afterwards: the user switched capture off mid-job. */
private class SwitchedOffMidJob(
    private val onFor: Int,
) : SenderSettings by MemorySettings() {
    private var asked = 0

    override fun smsCaptureEnabled() = ++asked <= onFor
}

/** STORY-171: captures are local entries (REQ-SMS-095/096), gated by the capture switch and never upload (REQ-AND-135). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalCaptureTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
    private val settings = MemorySettings()
    private val notices = mutableListOf<eg.masroof.data.db.CapturedTxEntity>()
    private val purchase = "Your card ending 4512 was charged EGP 1.00 at AMAZON"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun pipeline(
        settings: SenderSettings = this.settings,
        signal: LocalDataSignal = LocalDataSignal(),
    ) = SmsPipeline(
        db,
        settings,
        MemoryNotifications(),
        clock,
        MemoryGate(),
        FixedKey,
        object : eg.masroof.ingest.CapturedNotifier {
            override fun captured(item: eg.masroof.data.db.CapturedTxEntity) {
                notices += item
            }

            override fun cancel(localId: String) = Unit

            override fun cancelAll() = Unit
        },
        signal = signal,
    )

    private fun sms(
        body: String,
        sender: String = "CIB",
        at: Long = Instant.parse("2026-10-15T08:30:00Z").toEpochMilli(),
    ) = IncomingMessage(CaptureSource.SMS, sender, body, at)

    // ---- REQ-SMS-095

    @Test
    fun `REQ-SMS-095 the proven card family is written as one confirmed entry, its source and its Undo row in one go`() =
        runTest {
            val receivedAt = Instant.parse("2026-10-15T08:30:00Z").toEpochMilli()
            assertEquals(Disposition.RECORDED, pipeline().accept(sms(purchase, at = receivedAt)))
            val entry = db.expenses().all().single()
            assertEquals(100L, entry.amountMinor)
            assertEquals("EGP", entry.currency)
            assertEquals("EXPENSE", entry.type)
            assertEquals("CONFIRMED", entry.status)
            assertEquals("AMAZON", entry.merchant)
            assertEquals("4512", entry.instrumentLast4)
            assertEquals("CARD", entry.paymentMethod)
            assertEquals("no date in the text: the receive time", receivedAt, entry.occurredAtMillis)
            assertTrue("the receive time gives the time of day", entry.timeKnown)
            val source = db.sources().all().single()
            assertEquals(entry.id, source.expenseId)
            assertEquals("SMS", source.channel)
            assertEquals("CIB", source.senderId)
            assertEquals(receivedAt, source.receivedAtMillis)
            assertEquals(64, source.localSourceId.length)
            assertEquals(
                entry.id,
                db
                    .captured()
                    .all()
                    .single()
                    .expenseId,
            )
            assertEquals("Recorded 1 EGP at AMAZON", "Recorded 1 EGP at ${notices.single().label}")
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertEquals(
                "the entry is in the observed lists at once",
                listOf(entry.id),
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .map { it.id },
            )
        }

    @Test
    fun `REQ-SMS-095 a date-only message is stored at Cairo noon with the time marked unknown`() =
        runTest {
            val received = Instant.parse("2026-10-15T08:30:00Z").toEpochMilli()
            pipeline().accept(sms("Your card ending 4512 was charged EGP 99.00 at PHARMACY on 12/10/2026", at = received))
            val entry = db.expenses().all().single()
            val cairo = java.time.ZoneId.of("Africa/Cairo")
            assertEquals(java.time.LocalTime.NOON, Instant.ofEpochMilli(entry.occurredAtMillis).atZone(cairo).toLocalTime())
            assertFalse(entry.timeKnown)
        }

    @Test
    fun `REQ-SMS-095 income, an incoming transfer and an amount above 50000 EGP wait for review and never count as spending`() =
        runTest {
            val p = pipeline()
            p.accept(sms("تم استقبال تحويل لحظي إلى حسابكم 4471 بمبلغ 1,250.00 جم من OMAR KHALED SAMIR", sender = "BDC"))
            p.accept(sms("Your card ending 4512 was charged EGP 60,000.00 at JEWELLER", at = 2_000L))
            p.accept(sms("Your card ending 4512 was charged EGP 20.00 at KIOSK", at = 3_000L))
            val all = db.expenses().all().associateBy { it.amountMinor }
            assertEquals("PENDING_REVIEW", all.getValue(125_000L).status)
            assertEquals("INCOME", all.getValue(125_000L).type)
            assertEquals("4471", all.getValue(125_000L).instrumentLast4)
            assertEquals("PENDING_REVIEW", all.getValue(6_000_000L).status)
            assertEquals("EXPENSE", all.getValue(6_000_000L).type)
            assertEquals("CONFIRMED", all.getValue(2_000L).status)
            // STORY-178: a PENDING_REVIEW expense counts (one rule for Home and Insights); the incoming transfer never does.
            val total = db.expenses().observeTotal(0L, Long.MAX_VALUE).first()
            assertEquals("both purchases count, the incoming transfer does not", 6_002_000L, total)
        }

    @Test
    fun `REQ-SMS-095 an OTP stores nothing at all`() =
        runTest {
            assertEquals(Disposition.DROPPED, pipeline().accept(sms("Your OTP is 889900, do not share it")))
            assertTrue(db.expenses().all().isEmpty())
            assertTrue(db.sources().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
            assertTrue(db.reviews().all().isEmpty())
            assertTrue(notices.isEmpty())
        }

    @Test
    fun `REQ-SMS-095 a capture tells the screens once that something changed`() =
        runTest(UnconfinedTestDispatcher()) {
            val signal = LocalDataSignal()
            var signals = 0
            backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { signal.events.collect { signals++ } }
            pipeline(signal = signal).accept(sms(purchase))
            kotlinx.coroutines.yield()
            assertEquals(1, signals)
        }

    // ---- REQ-SMS-096

    @Test
    fun `REQ-SMS-096 the same SMS twice makes one entry, one source, one row and one notice`() =
        runTest {
            val p = pipeline()
            p.accept(sms(purchase))
            assertEquals(Disposition.RECORDED, p.accept(sms(purchase)))
            assertEquals(1, db.expenses().all().size)
            assertEquals(1, db.sources().all().size)
            assertEquals(1, db.captured().all().size)
            assertEquals(1, notices.size)
        }

    // ---- REQ-AND-135

    @Test
    fun `REQ-AND-135 a fresh install that never signed in records, with real preferences and no session anywhere`() =
        runTest {
            val real = PrefsSenderSettings(context.getSharedPreferences("fresh-senders", Context.MODE_PRIVATE))
            assertTrue("capture is on by default", real.smsCaptureEnabled())
            assertEquals(Disposition.RECORDED, pipeline(real).accept(sms(purchase)))
            assertEquals(1, db.expenses().all().size)
        }

    @Test
    fun `REQ-AND-135 the SMS capture switch persists and the receiver ignores a message when it is off - no job at all`() {
        val real = PrefsSenderSettings(context.getSharedPreferences("switch-senders", Context.MODE_PRIVATE))
        real.setSmsCaptureEnabled(false)
        assertFalse(PrefsSenderSettings(context.getSharedPreferences("switch-senders", Context.MODE_PRIVATE)).smsCaptureEnabled())
        val scheduler = FakeSmsScheduler()
        SmsHandoff(real, scheduler, MemoryGate()).handOff(listOf(IncomingSms("CIB", purchase, 1_000L)))
        assertTrue(scheduler.intake.isEmpty())
        // an unknown money-like sender is not even suggested while capture is off
        SmsHandoff(real, scheduler, MemoryGate()).handOff(listOf(IncomingSms("NewBank", purchase, 1_000L)))
        assertTrue(real.suggestions().isEmpty())
        real.setSmsCaptureEnabled(true)
        SmsHandoff(real, scheduler, MemoryGate()).handOff(listOf(IncomingSms("CIB", purchase, 1_000L)))
        assertEquals(1, scheduler.intake.size)
    }

    @Test
    fun `REQ-AND-135 notification capture off is ignored the same way`() {
        val real = PrefsNotificationSettings(context.getSharedPreferences("switch-ntf", Context.MODE_PRIVATE))
        val scheduler = FakeSmsScheduler()
        eg.masroof.ingest
            .NotificationHandoff(real, scheduler, MemoryGate())
            .handOff(eg.masroof.ingest.parse.NotificationApps.INSTAPAY_CANDIDATE, "InstaPay", "You sent EGP 250.00 to Sara", 1_000L)
        assertTrue(scheduler.intake.isEmpty())
    }

    @Test
    fun `REQ-AND-135 capture switched off while an intake job is already running writes nothing`() =
        runTest {
            // question 1: the early check; question 2: the check inside the write transaction (now off)
            assertEquals(Disposition.DROPPED, pipeline(SwitchedOffMidJob(onFor = 1)).accept(sms(purchase)))
            assertTrue(db.expenses().all().isEmpty())
            assertTrue(db.sources().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
            assertTrue(notices.isEmpty())
        }

    @Test
    fun `REQ-AND-135 a capture never enqueues an upload worker and never touches the network`() =
        runTest {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
            val workManager = WorkManager.getInstance(context)
            WorkManagerSmsScheduler(workManager).enqueueIntake(sms(purchase))
            val intake = workManager.getWorkInfosByTag(SMS_INTAKE_TAG).get()
            assertEquals("one intake job and nothing else", 1, intake.size)
            // run that job for real: it records locally
            val p = pipeline()
            val factory =
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SmsIntakeWorker(appContext, workerParameters, p)
                }
            val worker =
                TestListenableWorkerBuilder<SmsIntakeWorker>(context)
                    .setInputData(workDataOf("sender" to "CIB", "text" to purchase, "source" to "SMS", "ts" to 1_000L))
                    .setWorkerFactory(factory)
                    .build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(1, db.expenses().all().size)
            for (name in listOf(SMS_UPLOAD_WORK, SMS_UPLOAD_RETRY_WORK)) {
                assertTrue(name, workManager.getWorkInfosForUniqueWork(name).get().isEmpty())
            }
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-AND-135 no capture code path references an uploader, the API client or the outbox`() {
        val root = File("src/main/java/eg/masroof")
        val work = File(root, "ingest/SmsWork.kt").readText()
        val pipelineAndWorker =
            work.substring(work.indexOf("class SmsPipeline"), work.indexOf("/** Result of one upload pass")) +
                work.substring(work.indexOf("class SmsIntakeWorker"), work.indexOf("class SmsUploadWorker"))
        val others =
            listOf("ingest/SmsReceiver.kt", "ingest/NotificationCapture.kt", "ingest/Captured.kt", "MasroofApp.kt")
                .joinToString("\n") { File(root, it).readText() }
        val handoff = File(root, "ingest/SmsHandoff.kt").readText().substringAfter("class SmsHandoff")
        for ((where, text) in listOf("pipeline+worker" to pipelineAndWorker, "receivers+undo+app" to others + handoff)) {
            for (forbidden in listOf(
                "scheduleUpload",
                "ParsedRemote",
                "uploadParsed",
                "parsedOutbox()",
                "SmsUploader",
                "okhttp",
                "retrofit",
            )) {
                assertFalse("$where mentions $forbidden", text.contains(forbidden, ignoreCase = true))
            }
        }
    }

    // ---- fix round 1

    @Test
    fun `REQ-AND-135 Review Save writes a local entry and enqueues no upload work`() =
        runTest {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
            val workManager = WorkManager.getInstance(context)
            pipeline().accept(sms("Purchase of EGP 99.00 at PHARMACY on 12/03/2026 14:05"))
            val item = db.reviews().all().single()
            eg.masroof.ui.viewmodel
                .ReviewViewModel(db, Dispatchers.Unconfined, clock)
                .saveNow(item.id)
            val entry = db.expenses().all().single()
            assertEquals(9_900L, entry.amountMinor)
            assertEquals(
                item.id,
                db
                    .sources()
                    .all()
                    .single()
                    .localSourceId,
            )
            assertTrue(db.reviews().all().isEmpty())
            assertTrue(db.parsedOutbox().all().isEmpty())
            for (name in listOf(SMS_UPLOAD_WORK, SMS_UPLOAD_RETRY_WORK)) {
                assertTrue(name, workManager.getWorkInfosForUniqueWork(name).get().isEmpty())
            }
            assertTrue(workManager.getWorkInfosByTag(SMS_INTAKE_TAG).get().isEmpty())
        }

    @Test
    fun `REQ-AND-135 app start cancels upload jobs that an older version left in WorkManager`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        val request = androidx.work.OneTimeWorkRequestBuilder<eg.masroof.ingest.SmsUploadWorker>()
        for (name in listOf(SMS_UPLOAD_WORK, SMS_UPLOAD_RETRY_WORK)) {
            workManager.enqueueUniqueWork(
                name,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request.setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS).build(),
            )
        }
        eg.masroof.ingest.cancelLeftoverUploadWork(workManager)
        for (name in listOf(SMS_UPLOAD_WORK, SMS_UPLOAD_RETRY_WORK)) {
            assertTrue(name, workManager.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished })
        }
    }

    @Test
    fun `REQ-AND-135 an old upload job succeeds without opening the database or sending anything`() =
        runTest {
            val worker = TestListenableWorkerBuilder<eg.masroof.ingest.SmsUploadWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test
    fun `REQ-SMS-010 the USD fixture is never a confirmed entry and never counts in the total`() =
        runTest {
            val body = "Dear Customer, your card ending with 1234 was used for USD 20.00 at NETFLIX.COM on 12/03/2026 14:05."
            pipeline().accept(sms(body, at = Instant.parse("2026-03-12T12:10:00Z").toEpochMilli()))
            assertTrue(db.expenses().all().none { it.status == "CONFIRMED" })
            assertEquals(0L, db.expenses().observeTotal(0L, Long.MAX_VALUE).first())
            // whatever path leads here, a foreign currency waits for review and is not added to EGP spending
            val usd =
                eg.masroof.ingest.parse.ParsedRecord(
                    "",
                    CaptureSource.SMS,
                    eg.masroof.ingest.parse.TxKind.PURCHASE,
                    2_000L,
                    "USD",
                    1L,
                    "1234",
                    "CIB",
                    "CIB",
                    "CARD",
                    "NETFLIX.COM",
                )
            assertTrue(
                eg.masroof.ingest.CaptureRows
                    .needsReview(usd),
            )
            db.expenses().insert(
                eg.masroof.ingest.CaptureRows
                    .expense("u1", usd.copy(currency = "USD"), 1L)
                    .copy(status = "CONFIRMED"),
            )
            assertEquals("a confirmed USD row is not EGP spending", 0L, db.expenses().observeTotal(0L, Long.MAX_VALUE).first())
        }

    @Test
    fun `REQ-SMS-095 a message stating exactly 12 00 has a known time, a date-only message does not`() =
        runTest {
            val at = Instant.parse("2026-10-15T08:30:00Z").toEpochMilli()
            pipeline().accept(sms("Your card ending 4512 was charged EGP 5.00 at KIOSK on 12/10/2026 12:00", at = at))
            assertTrue(
                db
                    .expenses()
                    .all()
                    .single()
                    .timeKnown,
            )
        }

    @Test
    fun `REQ-SMS-097 Undo on a notice whose row is gone still takes the notice down`() =
        runTest {
            val withdrawn = mutableListOf<String>()
            val sink =
                object : eg.masroof.ingest.CapturedNotifier {
                    override fun captured(item: eg.masroof.data.db.CapturedTxEntity) = Unit

                    override fun cancel(localId: String) {
                        withdrawn += localId
                    }

                    override fun cancelAll() = Unit
                }
            val bank = "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED"
            val push = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
            val p = pipeline()
            p.accept(IncomingMessage(CaptureSource.NOTIFICATION, "com.egyptianbanks.instapay", push, 13_000L))
            val pushRow = db.captured().all().single()
            p.accept(IncomingMessage(CaptureSource.SMS, "BDC", bank, 10_000L))
            val result =
                eg.masroof.ingest
                    .CapturedActions(db, clock, notifier = sink)
                    .undo(pushRow.localId)
            assertFalse(result.removed)
            assertEquals(listOf(pushRow.localId), withdrawn)
        }

    @Test
    fun `REQ-AND-135 signed in, then logged out - a bank SMS is still recorded`() =
        runTest {
            val store =
                FakeSessionStore(
                    eg.masroof.data.auth.LoadedSession
                        .Found(session()),
                )
            val manager =
                eg.masroof.data.auth
                    .SessionManager(store, FakeAuthRemote(), FakeSettings(), Dispatchers.Unconfined)
            manager.logout()
            assertEquals(Disposition.RECORDED, pipeline().accept(sms(purchase)))
            assertEquals(1, db.expenses().all().size)
            assertEquals(1, db.sources().all().size)
        }

    // ---- Settings

    @Test
    fun `REQ-AND-135 the SMS switch setting is read and written through the view model`() =
        runTest {
            val real = PrefsSenderSettings(context.getSharedPreferences("vm-senders", Context.MODE_PRIVATE))
            val vm =
                SmsCaptureViewModel(
                    real,
                    MemoryNotifications(),
                    object : eg.masroof.ingest.CapturedAlertSettings {
                        override fun enabled() = true

                        override fun setEnabled(on: Boolean) = Unit
                    },
                    object : eg.masroof.ingest.NotificationPermission {
                        override fun granted() = true
                    },
                    db,
                    clock,
                    Dispatchers.Unconfined,
                )
            assertTrue(vm.smsCaptureEnabled.value)
            vm.setSmsCapture(false)
            assertFalse(vm.smsCaptureEnabled.value)
            assertFalse(real.smsCaptureEnabled())
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SmsCaptureSwitchTest {
    @get:Rule val compose = createComposeRule()

    private var toggled: Boolean? = null
    private var opened = 0

    private fun show(
        wanted: Boolean,
        permitted: Boolean,
    ) = compose.setContent {
        MasroofTheme {
            SmsCaptureSwitch(wanted, permitted, denied = false, onToggle = { toggled = it }, onOpenSettings = { opened++ })
        }
    }

    @Test
    fun `REQ-AND-135 the switch is on when wanted and permitted and there is no permission prompt`() {
        show(wanted = true, permitted = true)
        compose.onNodeWithText("SMS capture is on").assertIsDisplayed()
        compose.onNodeWithContentDescription("SMS capture").assertIsOn()
        assertTrue(compose.onAllNodesWithText("Permission needed").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `REQ-AND-135 a revoked RECEIVE_SMS shows the switch off with Permission needed and a button to the system settings`() {
        show(wanted = true, permitted = false)
        compose.onNodeWithContentDescription("SMS capture").assertIsOff()
        compose.onNodeWithText("Permission needed").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `REQ-AND-135 turning the switch reports the new state`() {
        show(wanted = false, permitted = true)
        compose.onNodeWithContentDescription("SMS capture").assertIsOff().performClick()
        assertEquals(true, toggled)
    }
}
