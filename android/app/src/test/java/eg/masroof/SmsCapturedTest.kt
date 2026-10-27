package eg.masroof

import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.LearnedTemplateEntity
import eg.masroof.ingest.AndroidCapturedNotifier
import eg.masroof.ingest.CAPTURED_CHANNEL_ID
import eg.masroof.ingest.CapturedActions
import eg.masroof.ingest.CapturedAlertSettings
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.NotificationPermission
import eg.masroof.ingest.PrefsCapturedAlertSettings
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.intakeResult
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
    private val notifier = RecordingNotifier()
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
            clock,
            MemoryGate(),
            FixedKey,
            notifier,
        )
    private val actions get() = CapturedActions(db, clock)

    private fun sms(
        body: String,
        at: Long = 1_000L,
    ) = IncomingMessage(CaptureSource.SMS, "CIB", body, at)

    @Test
    fun `REQ-SMS-080 an automatic record is remembered for Undo and announced with typed fields only`() =
        runTest {
            assertEquals(Disposition.RECORDED, pipeline.accept(sms(family)))
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
            assertEquals(Disposition.RECORDED, pipeline.accept(sms("Purchase of EGP 45.00 at BAKERY", at = 2_000L)))
            assertNotNull(notifier.items.single().templateId)
        }

    @Test
    fun `REQ-SMS-097 Undo soft deletes the local entry - it leaves the lists and totals and its source stays`() =
        runTest {
            pipeline.accept(sms(family))
            val captured = db.captured().all().single()
            val entryId = captured.expenseId!!
            assertEquals(50_000L, db.expenses().observeTotal(0L, Long.MAX_VALUE).first())
            val result = actions.undo(captured.localId)
            assertTrue(result.removed)
            val row = db.expenses().find(entryId)!!
            assertEquals(clock.millis(), row.deletedAtMillis)
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                db
                    .expenses()
                    .observeRecent(10)
                    .first()
                    .isEmpty(),
            )
            assertEquals(0L, db.expenses().observeTotal(0L, Long.MAX_VALUE).first())
            assertEquals("the source row stays, so the same message is never recorded again", 1, db.sources().all().size)
            assertTrue(
                "hidden from the chips",
                db
                    .captured()
                    .observeRecent(0L)
                    .first()
                    .isEmpty(),
            )
            assertTrue(db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-097 Undo of an entry the user already deleted stays deleted without an error, an edited entry is deleted too`() =
        runTest {
            pipeline.accept(sms(family))
            val captured = db.captured().all().single()
            val entryId = captured.expenseId!!
            db.expenses().softDelete(entryId, 5_000L)
            assertTrue(actions.undo(captured.localId).removed)
            assertEquals("the first deletion time is kept", 5_000L, db.expenses().find(entryId)!!.deletedAtMillis)

            pipeline.accept(sms("Your card ending 4512 was charged EGP 75.00 at NOON", at = 2_000L))
            val second = db.captured().all().single { it.amountMinor == 7_500L }
            val edited = db.expenses().find(second.expenseId!!)!!
            db.expenses().update(edited.copy(notes = "gift", updatedAtMillis = edited.updatedAtMillis + 1))
            assertTrue(actions.undo(second.localId).removed)
            assertNotNull(db.expenses().find(second.expenseId)!!.deletedAtMillis)
        }

    @Test
    fun `REQ-SMS-097 Undo of an unknown notice changes nothing and a capture older than seven days has no chip`() =
        runTest {
            assertFalse(actions.undo("nothing").removed)
            pipeline.accept(sms(family))
            val later = Clock.fixed(Instant.parse("2026-10-23T09:00:01Z"), ZoneOffset.UTC)
            assertTrue(CapturedActions(db, later).observeRecent().first().isEmpty())
            assertEquals(1, CapturedActions(db, clock).observeRecent().first().size)
        }

    @Test
    fun `REQ-SMS-097 Undo of a record a learned format made offers to stop learning it, and yes forgets the format`() =
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

    @Test
    fun `REQ-SMS-096 REQ-SMS-097 an undone entry and the same SMS again - nothing is recorded, no notice`() =
        runTest {
            pipeline.accept(sms(family))
            val localId =
                db
                    .captured()
                    .all()
                    .single()
                    .localId
            actions.undo(localId)
            val noticesBefore = notifier.items.size
            assertEquals(Disposition.RECORDED, pipeline.accept(sms(family)))
            assertEquals(1, db.expenses().all().size)
            assertEquals(1, db.sources().all().size)
            assertEquals(noticesBefore, notifier.items.size)
            assertTrue(
                "still deleted",
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            // even after the 7-day chip row is purged, the source row still refuses it (within 30 days)
            val inAWeek = Clock.fixed(Instant.parse("2026-10-23T09:00:01Z"), ZoneOffset.UTC)
            val later = SmsPipeline(db, MemorySettings(), MemoryNotifications(), inAWeek, MemoryGate(), FixedKey, notifier)
            later.accept(sms(family))
            assertEquals(1, db.expenses().all().size)
            assertEquals(1, notifier.items.size)
        }

    @Test
    fun `REQ-SMS-097 the notification twin of an undone transfer adds its source and does not bring the entry back`() =
        runTest {
            val bank = "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED"
            pipeline.accept(IncomingMessage(CaptureSource.SMS, "BDC", bank, 10_000L))
            val smsRow = db.captured().all().single()
            actions.undo(smsRow.localId)
            val text = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
            pipeline.accept(IncomingMessage(CaptureSource.NOTIFICATION, "com.egyptianbanks.instapay", text, 13_000L))
            assertEquals(1, db.expenses().all().size)
            assertEquals(2, db.sources().all().size)
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertEquals(1, notifier.items.size)
        }

    @Test
    fun `REQ-SMS-095 the database refusing the write leaves no row and no notice and the intake is tried again`() =
        runTest {
            // a trigger makes the LAST insert of the transaction (captured_tx) fail: everything before it must roll back
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER no_room BEFORE INSERT ON captured_tx BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END",
            )
            val failed = runCatching { pipeline.accept(sms(family)) }
            assertTrue(failed.isFailure)
            assertTrue(db.expenses().all().isEmpty())
            assertTrue(db.sources().all().isEmpty())
            assertTrue(db.captured().all().isEmpty())
            assertTrue(notifier.items.isEmpty())
            // the job result for a full database is retry, with the message still in the job input
            assertEquals(ListenableWorker.Result.retry(), intakeResultFor(SQLiteFullException("full"), attempt = 1))
            assertEquals(ListenableWorker.Result.failure(), intakeResultFor(SQLiteFullException("full"), attempt = 10))
            // space is back: the retried job records it
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER no_room")
            assertEquals(Disposition.RECORDED, pipeline.accept(sms(family)))
            assertEquals(1, db.expenses().all().size)
        }

    private suspend fun intakeResultFor(
        error: Exception,
        attempt: Int,
    ) = intakeResult(attempt) { throw error }

    @Test
    fun `REQ-SMS-096 the same message accepted again keeps the same entry and is announced once`() =
        runTest {
            pipeline.accept(sms(family))
            val first = db.captured().all().single()
            pipeline.accept(sms(family))
            assertEquals(
                first.expenseId,
                db
                    .captured()
                    .all()
                    .single()
                    .expenseId,
            )
            assertEquals(1, notifier.items.size)
            assertEquals(1, db.expenses().all().size)
            assertTrue("nothing is queued", db.parsedOutbox().all().isEmpty())
        }

    @Test
    fun `REQ-SMS-082 a transfer is announced with the institution only, never the counterparty's name`() =
        runTest {
            val incoming = "تم إستقبال تحويل لحظي إلى حسابكم 7781 بمبلغ 900.00 جم من MONA ADEL"
            assertEquals(Disposition.RECORDED, pipeline.accept(sms(incoming)))
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
