package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.Money
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.NewExpense
import eg.masroof.data.RefreshOutcome
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.remote.UploadResult
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTest {
    private lateinit var db: AppDatabase
    private lateinit var remote: FakeRemote
    private lateinit var repo: DefaultExpenseRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        remote = FakeRemote()
        repo = DefaultExpenseRepository(db, remote, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun entry(minor: Long = 12_500) = NewExpense(Money(minor), clock.millis(), null, null, "lunch")

    @Test
    fun `REQ-AND-OUTBOX add is saved locally first and queued`() =
        runTest {
            repo.add(entry())
            val rows = repo.observeAll().first()
            assertEquals(1, rows.size)
            assertTrue(rows[0].pending)
            assertEquals(1, repo.observePendingCount().first())
        }

    @Test
    fun `REQ-AND-OUTBOX month total includes offline entries in Cairo month`() =
        runTest {
            repo.add(entry(10_000))
            repo.add(entry(2_500))
            assertEquals(Money(12_500), repo.observeMonthTotal().first())
        }

    @Test
    fun `REQ-AND-OUTBOX sync uploads with the client id as idempotency key and swaps in the server row`() =
        runTest {
            val id = repo.add(entry())
            remote.uploadResult = { UploadResult.Created(expense("server-1", it.amountMinor)) }

            val report = repo.syncOutbox()

            assertEquals(1, report.uploaded)
            assertEquals(listOf(id), remote.uploadedKeys)
            val rows = repo.observeAll().first()
            assertEquals(listOf("server-1"), rows.map { it.id })
            assertTrue(rows.none { it.pending })
            assertEquals(0, repo.observePendingCount().first())
        }

    @Test
    fun `REQ-AND-OUTBOX unreachable server keeps the entry and asks for retry with backoff`() =
        runTest {
            repo.add(entry())
            repo.add(entry(500))
            remote.uploadResult = { UploadResult.Retry }

            val report = repo.syncOutbox()

            assertTrue(report.retry)
            assertEquals("stops after the first failure", 1, remote.uploadedKeys.size)
            assertEquals(2, repo.observePendingCount().first())
            assertEquals(1, db.outbox().all().sumOf { it.attempts })
        }

    @Test
    fun `REQ-AND-OUTBOX rejected entries leave the queue but stay visible`() =
        runTest {
            repo.add(entry())
            remote.uploadResult = { UploadResult.Rejected }

            val report = repo.syncOutbox()

            assertEquals(1, report.rejected)
            assertEquals(false, report.retry)
            assertEquals(0, repo.observePendingCount().first())
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun `REQ-AND-OUTBOX refresh reports offline and keeps cached rows`() =
        runTest {
            remote.expenses = listOf(expense("a", 100))
            assertEquals(RefreshOutcome.Ok, repo.refresh())
            remote.offline = true
            assertEquals(RefreshOutcome.Offline, repo.refresh())
            assertEquals(listOf("a"), repo.observeAll().first().map { it.id })
        }

    @Test
    fun `REQ-AND-OUTBOX refresh never drops locally pending rows`() =
        runTest {
            val id = repo.add(entry())
            remote.expenses = listOf(expense("a", 100))
            remote.uploadResult = { UploadResult.Retry }
            repo.refresh()
            assertTrue(repo.observeAll().first().any { it.id == id && it.pending })
        }
}
