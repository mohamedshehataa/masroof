package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.UuidIdFactory
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.remote.ParsedRemote
import eg.masroof.data.remote.SmsUploadResult
import eg.masroof.ingest.DeviceIdProvider
import eg.masroof.ingest.ExpenseListRefresher
import eg.masroof.ingest.SmsUploader
import eg.masroof.ingest.UploadedRecordsRefresher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** After captured records reach the server the screens get one signal per pass and the app fetches NO expense list (REQ-AND-130). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturedRefreshTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-26T21:05:00Z"), ZoneOffset.UTC)
    private val device =
        object : DeviceIdProvider {
            override fun get() = "6f1c1b7e-0000-4000-8000-000000000001"
        }
    private val signal = LocalDataSignal()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun queue(vararg ids: String) {
        for ((i, id) in ids.withIndex()) {
            db.parsedOutbox().insert(
                ParsedOutboxEntity(
                    localId = id,
                    source = "SMS",
                    kind = "TRANSFER_IN",
                    amountMinor = 100L * (i + 1),
                    currency = "EGP",
                    occurredAtMillis = clock.millis() + i * 60 * 60 * 1000L,
                    instrumentLast4 = "1094",
                    senderId = "BDC",
                    institution = "Bank $i",
                    paymentMethod = null,
                    merchant = null,
                    createdAtMillis = clock.millis(),
                ),
            )
        }
    }

    private fun uploader(answer: SmsUploadResult): Pair<SmsUploader, DefaultExpenseRepository> {
        val parsed =
            object : ParsedRemote {
                override suspend fun uploadParsed(
                    deviceId: String,
                    item: ParsedOutboxEntity,
                ) = answer
            }
        val repo = DefaultExpenseRepository(db, clock, UuidIdFactory(), signal)
        return SmsUploader(db, parsed, device, clock, MemoryGate(), refresher = ExpenseListRefresher(signal)) to repo
    }

    @Test
    fun `REQ-SMS-094 a pass that sent two records signals once and fetches no list`() =
        runTest(UnconfinedTestDispatcher()) {
            var signals = 0
            backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { signal.events.collect { signals++ } }
            queue("a", "b")
            val (uploader, repo) = uploader(SmsUploadResult.Sent("server-1"))
            val report = uploader.uploadPending()
            assertEquals(2, report.sent)
            kotlinx.coroutines.yield()
            assertEquals(1, signals)
            // the server's rows are not pulled into the list
            assertTrue(repo.observeAll().first().isEmpty())
        }

    @Test
    fun `REQ-SMS-094 a pass that sent nothing neither refreshes nor signals`() =
        runTest(UnconfinedTestDispatcher()) {
            var signals = 0
            backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { signal.events.collect { signals++ } }
            queue("a")
            val (retrying, repo) = uploader(SmsUploadResult.Retry(null))
            assertEquals(0, retrying.uploadPending().sent)
            assertEquals(0, signals)
            assertTrue(repo.observeAll().first().isEmpty())
            // an empty queue does nothing either
            db.parsedOutbox().delete("a")
            uploader(SmsUploadResult.Sent("x")).first.uploadPending()
            assertEquals(0, signals)
        }

    @Test
    fun `REQ-SMS-094 a refresher that throws cannot fail the pass or lose its retry`() =
        runTest(UnconfinedTestDispatcher()) {
            queue("a", "b")
            val parsed =
                object : ParsedRemote {
                    override suspend fun uploadParsed(
                        deviceId: String,
                        item: ParsedOutboxEntity,
                    ) = if (item.localId == "a") SmsUploadResult.Sent("server-1") else SmsUploadResult.Retry(30)
                }
            val broken =
                object : UploadedRecordsRefresher {
                    override suspend fun refreshAfterUpload() = error("boom")
                }
            val report = SmsUploader(db, parsed, device, clock, MemoryGate(), refresher = broken).uploadPending()
            assertEquals(1, report.sent)
            assertTrue(report.retry)
            assertEquals(30L, report.retryAfterSeconds)
        }
}
