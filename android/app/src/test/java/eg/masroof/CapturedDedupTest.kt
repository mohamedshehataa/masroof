package eg.masroof

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CapturedTxEntity
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.ingest.AndroidCapturedNotifier
import eg.masroof.ingest.CapturedAlertSettings
import eg.masroof.ingest.CapturedNotifier
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

private class NoticeLog : CapturedNotifier {
    val shown = mutableListOf<CapturedTxEntity>()
    val withdrawn = mutableListOf<String>()

    override fun captured(item: CapturedTxEntity) {
        shown += item
    }

    override fun cancel(localId: String) {
        withdrawn += localId
    }

    override fun cancelAll() = Unit
}

private class AlertsOn : CapturedAlertSettings {
    override fun enabled() = true

    override fun setEnabled(on: Boolean) = Unit
}

/** One transfer announced by two channels is one record (REQ-SMS-090); a notice says what happened (REQ-SMS-091). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedDedupTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-26T21:05:00Z"), ZoneOffset.UTC)
    private val notices = NoticeLog()
    private val sms =
        "تم إستقبال تحويل لحظي إلى حسابكم 1094 بمبلغ 1.00 جم من MOHAMED MAHMOUD AHMED في 23:04 يوم 10/26/26 " +
            "رقم المعاملة bda86be1 للمزيد أتصل ب 16990"
    private val push = "InstaPay\nYou have received  1.00 EGP from mohamed.shehataaaaa@instapay"
    private val at = Instant.parse("2026-10-26T20:04:10Z").toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val pipeline get() =
        SmsPipeline(db, MemorySettings(), MemoryNotifications(), FakeSmsScheduler(), clock, MemoryGate(), FixedKey, notices)

    private fun bankSms(
        atMillis: Long = at,
        text: String = sms,
    ) = IncomingMessage(CaptureSource.SMS, "BDC", text, atMillis)

    private fun appPush(atMillis: Long = at + 3_000) =
        IncomingMessage(CaptureSource.NOTIFICATION, "com.egyptianbanks.instapay", push, atMillis)

    @Test
    fun `REQ-SMS-090 the bank SMS first, then the InstaPay notification of the same transfer - one record, one notice`() =
        runTest {
            assertEquals(Disposition.QUEUED, pipeline.accept(bankSms()))
            assertEquals(Disposition.QUEUED, pipeline.accept(appPush()))
            assertEquals(
                "SMS",
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .source,
            )
            assertEquals(
                "1094",
                db
                    .parsedOutbox()
                    .all()
                    .single()
                    .instrumentLast4,
            )
            assertEquals(1, db.captured().all().size)
            assertEquals(1, notices.shown.size)
        }

    @Test
    fun `REQ-SMS-090 the notification first, then the bank SMS - the richer SMS replaces it and the first notice is withdrawn`() =
        runTest {
            pipeline.accept(appPush())
            val first = db.parsedOutbox().all().single()
            pipeline.accept(bankSms())
            val kept = db.parsedOutbox().all().single()
            assertEquals("SMS", kept.source)
            assertEquals("1094", kept.instrumentLast4)
            assertEquals(1, db.captured().all().size)
            assertEquals(listOf(first.localId), notices.withdrawn)
            assertEquals(2, notices.shown.size)
        }

    @Test
    fun `REQ-SMS-090 when the first record is already uploaded the second one is dropped`() =
        runTest {
            pipeline.accept(appPush())
            val first = db.parsedOutbox().all().single()
            db.parsedOutbox().delete(first.localId) // as after a successful upload
            db.captured().setExpenseId(first.localId, "expense-1")
            pipeline.accept(bankSms())
            assertTrue(db.parsedOutbox().all().isEmpty())
            assertEquals(listOf(first.localId), db.captured().all().map { it.localId })
            assertEquals(1, notices.shown.size)
        }

    @Test
    fun `REQ-SMS-090 two equal transfers more than five minutes apart are two transfers`() =
        runTest {
            pipeline.accept(bankSms())
            pipeline.accept(appPush(atMillis = at + 6 * 60_000))
            assertEquals(2, db.parsedOutbox().all().size)
            assertEquals(2, notices.shown.size)
        }

    @Test
    fun `REQ-SMS-090 a different amount is a different transfer`() =
        runTest {
            pipeline.accept(bankSms())
            pipeline.accept(appPush().copy(text = "InstaPay\nYou have received  2.00 EGP from mohamed.shehataaaaa@instapay"))
            assertEquals(2, db.parsedOutbox().all().size)
        }

    @Test
    fun `REQ-SMS-090 two equal SMS of the same channel are never merged`() =
        runTest {
            pipeline.accept(bankSms())
            pipeline.accept(bankSms(atMillis = at + 60_000, text = sms.replace("bda86be1", "cda86be2")))
            assertEquals(2, db.parsedOutbox().all().size)
        }

    @Test
    fun `REQ-SMS-091 received money says Received via the bank, sent money says Sent, purchases keep Recorded`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidCapturedNotifier(context, AlertsOn())
        val manager = context.getSystemService(NotificationManager::class.java)

        fun titleOf(item: CapturedTxEntity): String {
            notifier.captured(item)
            return shadowOf(manager)
                .allNotifications
                .last()
                .extras
                .getCharSequence("android.title")
                .toString()
        }

        fun item(
            id: String,
            minor: Long,
            label: String?,
            kind: String,
        ) = CapturedTxEntity(id, null, minor, "EGP", label, "SMS", null, 1L, kind = kind)
        assertEquals("Received 1 EGP via Banque du Caire", titleOf(item("a", 100, "Banque du Caire", "INCOME")))
        assertEquals("Received 1 EGP via InstaPay", titleOf(item("b", 100, "InstaPay", "TRANSFER_IN")))
        assertEquals("Sent 250 EGP via InstaPay", titleOf(item("c", 25_000, "InstaPay", "TRANSFER_OUT")))
        assertEquals("Recorded 500 EGP at AMAZON", titleOf(item("d", 50_000, "AMAZON", "PURCHASE")))
        assertEquals("Received 1 EGP", titleOf(item("e", 100, null, "INCOME")))
    }

    @Test
    fun `REQ-SMS-092 received money is cached with its review state and never counted as spending`() =
        runTest {
            db.expenses().upsert(
                listOf(
                    ExpenseEntity("e1", 5_000, "EGP", 1_000L, "Shop", null, null, null, pending = false),
                    ExpenseEntity(
                        "i1",
                        100,
                        "EGP",
                        2_000L,
                        "Banque du Caire",
                        null,
                        null,
                        null,
                        pending = false,
                        type = "INCOME",
                        status = "PENDING_REVIEW",
                    ),
                ),
            )
            val listed = db.expenses().observeAll().first()
            assertEquals(listOf("i1", "e1"), listed.map { it.id })
            assertEquals("PENDING_REVIEW", listed.first().status)
            assertEquals(5_000L, db.expenses().observeTotal(0L, 10_000L).first())
        }
}
