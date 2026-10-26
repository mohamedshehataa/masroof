package eg.masroof

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.LearnedTemplateEntity
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.remote.ExpenseDeleter
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.AndroidCapturedNotifier
import eg.masroof.ingest.CAPTURED_CHANNEL_ID
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.CapturedAlertSettings
import eg.masroof.ingest.CapturedLocks
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.NotificationPermission
import eg.masroof.ingest.PrefsCapturedAlertSettings
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.viewmodel.SmsCaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class FakeDeleter(
    var ok: Boolean = true,
) : ExpenseDeleter {
    val deleted = mutableListOf<String>()

    override suspend fun deleteExpense(id: String): Boolean {
        if (ok) deleted += id
        return ok
    }
}

private class MemoryAlerts(
    var on: Boolean = true,
) : CapturedAlertSettings {
    override fun enabled() = on

    override fun setEnabled(on: Boolean) {
        this.on = on
    }
}

private class RecordingNotifier : CapturedNotifier {
    val items = mutableListOf<CapturedTxEntity>()
    val cancelled = mutableListOf<String>()

    override fun captured(item: CapturedTxEntity) {
        items += item
    }

    override fun cancel(localId: String) {
        cancelled += localId
    }

    override fun cancelAll() = Unit
}

private class FakePermission(
    var granted: Boolean,
) : NotificationPermission {
    override fun granted() = granted
}

/** Round 18: the safety net for automatic capture, the "Recorded ... Undo" notification and the Undo itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsCapturedTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
    private val scheduler = FakeSmsScheduler()
    private val notifier = RecordingNotifier()
    private val deleter = FakeDeleter()
    private val family = "Your card ending 4512 was charged EGP 500.00 at AMAZON"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val pipeline get() =
        SmsPipeline(
            db,
            MemorySettings(),
            MemoryNotifications(),
            scheduler,
            clock,
            MemoryGate(),
            FixedKey,
            notifier,
        )
    private val actions get() = CapturedActions(db, deleter, clock)

    private fun sms(
        body: String,
        at: Long = 1_000L,
    ) = IncomingMessage(CaptureSource.SMS, "CIB", body, at)

    @Test
    fun `REQ-SMS-080 an automatic record is remembered for Undo and announced with typed fields only`() =
        runTest {
            assertEquals(Disposition.QUEUED, pipeline.accept(sms(family)))
            val item = db.captured().all().single()
            assertEquals(50_000L, item.amountMinor)
            assertEquals("EGP", item.currency)
            assertEquals("AMAZON", item.label)
            assertNull("a proven family has no template", item.templateId)
            assertEquals(item, notifier.items.single())
            // the same message again is the same record: not a second notice
            pipeline.accept(sms(family))
            assertEquals(1, notifier.items.size)
            assertEquals(1, db.captured().all().size)
        }

    @Test
    fun `REQ-SMS-080 a review item is never announced and a one-tap Save is not an automatic record`() =
        runTest {
            assertEquals(Disposition.REVIEW, pipeline.accept(sms("Purchase of EGP 99.00 at PHARMACY")))
            assertTrue(notifier.items.isEmpty())
            assertTrue(db.captured().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-080 a learned format that recorded a message remembers its template`() =
        runTest {
            pipeline.accept(sms("Purchase of EGP 99.00 at PHARMACY"))
            val id =
                db
                    .reviews()
                    .all()
                    .single()
                    .id
            val template =
                eg.masroof.ingest.parse.TemplateShape
                    .of("Purchase of EGP 99.00 at PHARMACY")!!
            db.reviews().delete(id)
            db.learnedTemplates().upsert(
                LearnedTemplateEntity(
                    eg.masroof.ingest.TemplateLearning
                        .templateId("CIB", template.signature),
                    "CIB",
                    template.signature,
                    "PURCHASE",
                    -1,
                    -1,
                    1L,
                ),
            )
            assertEquals(Disposition.QUEUED, pipeline.accept(sms("Purchase of EGP 45.00 at BAKERY", at = 2_000L)))
            assertNotNull(notifier.items.single().templateId)
        }

    @Test
    fun `REQ-SMS-081 Undo of a record still in the outbox removes it from the outbox`() =
        runTest {
            pipeline.accept(sms(family))
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            val result = actions.undo(localId)
            assertTrue(result.removed)
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
            assertTrue("nothing was uploaded, so nothing is deleted", deleter.deleted.isEmpty())
        }

    @Test
    fun `REQ-SMS-081 Undo of an uploaded record deletes the expense through the API`() =
        runTest {
            pipeline.accept(sms(family))
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            // the upload happened and the server said which expense it created
            val remote =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: ParsedOutboxEntity,
                    ) = SmsUploadResult.Sent("3f2b1c64-1111-4222-8333-444455556666")
                }
            val device =
                object : DeviceIdProvider {
                    override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
                }
            SmsUploader(db, remote, device, clock, MemoryGate()).uploadPending()
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertEquals("3f2b1c64-1111-4222-8333-444455556666", db.captured().find(localId)!!.expenseId)
            db.expenses().upsert(
                ExpenseEntity("3f2b1c64-1111-4222-8333-444455556666", 50_000, "EGP", 1L, "AMAZON", null, null, null, false),
            )
            val result = actions.undo(localId)
            assertTrue(result.removed)
            assertEquals(listOf("3f2b1c64-1111-4222-8333-444455556666"), deleter.deleted)
            assertTrue(db.captured().all().isEmpty())
            assertTrue(
                "the cached row goes too",
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-SMS-081 a failed delete (offline) leaves everything as it was so Undo can be tried again`() =
        runTest {
            db.captured().insert(CapturedTxEntity("c1", "e-1", 50_000, "EGP", "AMAZON", "SMS", null, 1L))
            deleter.ok = false
            val result = actions.undo("c1")
            assertFalse(result.removed)
            assertNotNull(db.captured().find("c1"))
            deleter.ok = true
            assertTrue(actions.undo("c1").removed)
        }

    @Test
    fun `REQ-SMS-081 Undo of a record a learned format made offers to stop learning it, and yes forgets the format`() =
        runTest {
            db.learnedTemplates().upsert(LearnedTemplateEntity("t1", "CIB", "shape", "PURCHASE", -1, -1, 1L))
            db.captured().insert(CapturedTxEntity("c1", "e-1", 50_000, "EGP", "AMAZON", "SMS", "t1", 1L))
            val result = actions.undo("c1")
            assertEquals("t1", result.templateId)
            assertEquals(1, db.learnedTemplates().all().size)
            actions.stopLearning("t1")
            assertTrue(db.learnedTemplates().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-082 the notification holds amount, currency and label only - never message text - and is quiet`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val alerts = MemoryAlerts()
        val item = CapturedTxEntity("c1", null, 50_000, "EGP", "AMAZON", "SMS", null, 1L)
        AndroidCapturedNotifier(context, alerts).captured(item)
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.single()
        val title = posted.extras.getCharSequence("android.title").toString()
        val text = posted.extras.getCharSequence("android.text").toString()
        assertEquals("Recorded 500 EGP at AMAZON", title)
        for (shown in listOf(title, text, posted.extras.toString())) {
            assertFalse(shown.contains("card ending"))
            assertFalse(shown.contains("4512"))
        }
        assertEquals("one Undo action", 1, posted.actions.size)
        assertEquals("Undo", posted.actions[0].title.toString())
        val channel = manager.getNotificationChannel(CAPTURED_CHANNEL_ID)
        assertEquals("Captured transactions", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull("no sound", channel.sound)
        assertFalse("no vibration", channel.shouldVibrate())
    }

    @Test
    fun `REQ-SMS-082 the channel toggle in Settings turns the notification off`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val alerts = MemoryAlerts(on = false)
        val item = CapturedTxEntity("c1", null, 50_000, "EGP", null, "SMS", null, 1L)
        AndroidCapturedNotifier(context, alerts).captured(item)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        alerts.setEnabled(true)
        AndroidCapturedNotifier(context, alerts).captured(item)
        assertEquals(
            "Recorded 500 EGP",
            shadowOf(manager)
                .allNotifications
                .single()
                .extras
                .getCharSequence("android.title")
                .toString(),
        )
    }

    @Test
    fun `REQ-SMS-082 without the notification permission nothing is posted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AndroidCapturedNotifier(context, MemoryAlerts()).captured(CapturedTxEntity("c1", null, 100, "EGP", null, "SMS", null, 1L))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private val device =
        object : DeviceIdProvider {
            override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
        }

    @Test
    fun `REQ-SMS-081 Undo while the upload is in flight is remembered and the expense is deleted when the server answers`() =
        runTest {
            pipeline.accept(sms(family))
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            val locks = CapturedLocks()
            val racing = CapturedActions(db, deleter, clock, locks)
            var undoDuringUpload: eg.masroof.ingest.UndoResult? = null
            var serverReceived = 0
            val remote =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: ParsedOutboxEntity,
                    ): SmsUploadResult {
                        // the user taps Undo while this request is on the wire
                        undoDuringUpload = racing.undo(localId)
                        serverReceived++
                        return SmsUploadResult.Sent("3f2b1c64-1111-4222-8333-000000000005")
                    }
                }
            SmsUploader(db, remote, device, clock, MemoryGate(), deleter, locks).uploadPending()
            assertTrue(undoDuringUpload!!.removed)
            assertTrue("remembered, not half applied", undoDuringUpload!!.deferred)
            assertEquals(1, serverReceived)
            assertEquals("the expense the server made is deleted", listOf("3f2b1c64-1111-4222-8333-000000000005"), deleter.deleted)
            assertTrue(db.captured().all().isEmpty())
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-081 an Undo with no server id yet stays remembered and is finished by a later pass`() =
        runTest {
            db.captured().insert(CapturedTxEntity("c1", null, 50_000, "EGP", "AMAZON", "SMS", null, clock.millis()))
            val result = actions.undo("c1")
            assertTrue(result.removed && result.deferred)
            assertTrue(
                "hidden from the chips at once",
                db
                    .captured()
                    .observeRecent(0L)
                    .first()
                    .isEmpty(),
            )
            assertEquals(1, db.captured().undoRequests().size)
            // the id arrives later (a retry of the upload told us)
            db.captured().setExpenseId("c1", "e-77")
            val remote =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: ParsedOutboxEntity,
                    ) = SmsUploadResult.Sent()
                }
            SmsUploader(db, remote, device, clock, MemoryGate(), deleter).uploadPending()
            assertEquals(listOf("e-77"), deleter.deleted)
            assertTrue(db.captured().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-081 a lost answer then a retry learns the expense id from the duplicate answer and Undo works`() =
        runTest {
            pipeline.accept(sms(family))
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            var calls = 0
            val remote =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: ParsedOutboxEntity,
                    ): SmsUploadResult {
                        calls++
                        // the first answer is lost; the retry gets DUPLICATE, which now carries the id of the expense
                        return if (calls == 1) SmsUploadResult.Retry(null) else SmsUploadResult.Sent("3f2b1c64-1111-4222-8333-000000000010")
                    }
                }
            val uploader = SmsUploader(db, remote, device, clock, MemoryGate(), deleter)
            assertTrue(uploader.uploadPending().retry)
            uploader.uploadPending()
            assertEquals("3f2b1c64-1111-4222-8333-000000000010", db.captured().find(localId)!!.expenseId)
            assertTrue(actions.undo(localId).removed)
            assertEquals(listOf("3f2b1c64-1111-4222-8333-000000000010"), deleter.deleted)
        }

    @Test
    fun `REQ-SMS-081 the same message accepted again after its upload keeps the expense id and is announced once`() =
        runTest {
            pipeline.accept(sms(family))
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            val remote =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: ParsedOutboxEntity,
                    ) = SmsUploadResult.Sent("e-9")
                }
            SmsUploader(db, remote, device, clock, MemoryGate()).uploadPending()
            assertEquals("e-9", db.captured().find(localId)!!.expenseId)
            pipeline.accept(sms(family))
            assertEquals("e-9", db.captured().find(localId)!!.expenseId)
            assertEquals(1, notifier.items.size)
            assertTrue("nothing is queued a second time", db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-082 a transfer is announced with the institution only, never the counterparty's name`() =
        runTest {
            val incoming = "تم إستقبال تحويل لحظي إلى حسابكم 7781 بمبلغ 900.00 جم من MONA ADEL"
            assertEquals(Disposition.QUEUED, pipeline.accept(sms(incoming)))
            val label =
                db
                    .captured()
                    .all()
                    .single()
                    .label
            assertFalse(label.orEmpty().contains("MONA"))
            assertFalse(label.orEmpty().contains("ADEL"))
        }

    @Test
    fun `REQ-SMS-082 the switch shows ON only when the permission is granted, and a fresh install without it is OFF`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fresh = PrefsCapturedAlertSettings(context.getSharedPreferences("fresh-alerts", Context.MODE_PRIVATE))
        assertTrue("the preference itself defaults to on", fresh.enabled())
        val permission = FakePermission(granted = false)

        fun viewModel() = SmsCaptureViewModel(MemorySettings(), MemoryNotifications(), fresh, permission, db, clock, Dispatchers.Unconfined)
        val vm = viewModel()
        assertFalse("no permission: the switch is OFF, whatever the preference says", vm.capturedAlertsEnabled.value)
        vm.setCapturedAlerts(true)
        assertFalse("asking for ON without the permission does not make it ON", vm.capturedAlertsEnabled.value)
        permission.granted = true
        vm.onNotificationPermission()
        assertTrue("the user granted it: ON", vm.capturedAlertsEnabled.value)
        vm.setCapturedAlerts(false)
        assertFalse(vm.capturedAlertsEnabled.value)
    }

    @Test
    fun `REQ-SMS-082 every captured notification can be cancelled at once and one by one`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidCapturedNotifier(context, MemoryAlerts())
        notifier.captured(CapturedTxEntity("c1", null, 100, "EGP", null, "SMS", null, 1L))
        notifier.captured(CapturedTxEntity("c2", null, 200, "EGP", null, "SMS", null, 1L))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, shadowOf(manager).allNotifications.size)
        notifier.cancel("c1")
        assertEquals(1, shadowOf(manager).allNotifications.size)
        notifier.cancelAll()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
