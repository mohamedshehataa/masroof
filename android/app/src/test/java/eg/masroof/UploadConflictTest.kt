package eg.masroof

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.Money
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.NewExpense
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.legacyTokenMigration
import eg.masroof.data.remote.ProblemInfo
import eg.masroof.data.remote.UploadResult
import eg.masroof.data.remote.classifyUploadStatus
import eg.masroof.data.remote.parseProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The backend's three createExpense 409s (ExpenseService.create), as the app sees them. */
private const val P = "https://masroof.app/problems/"
private const val T_PROGRESS = P + "idempotency-in-progress"
private const val T_REUSED = P + "idempotency-key-reused"
private const val T_DELETED = P + "idempotency-expense-deleted"
private const val T_RATE = P + "rate-limited"
private const val TITLE_CONCURRENT = "Conflict"
private const val TITLE_REUSED = "Idempotency key reused"

private fun problem(
    type: String?,
    title: String? = null,
) = ProblemInfo(type, title)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadConflictTest {
    @get:Rule val tmp = TemporaryFolder()

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

    private fun entry() = NewExpense(Money(12_500), clock.millis(), null, null, "lunch")

    // ---- classification, one test per 409 cause

    @Test
    fun `REQ-AND-015 type idempotency-in-progress is retried with a cap`() {
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, problem(T_PROGRESS, TITLE_CONCURRENT)))
    }

    @Test
    fun `REQ-AND-015 type idempotency-key-reused is permanent`() {
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, problem(T_REUSED, TITLE_REUSED)))
    }

    @Test
    fun `REQ-AND-015 type idempotency-expense-deleted is permanent`() {
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, problem(T_DELETED, "anything")))
    }

    @Test
    fun `REQ-AND-015 type wins over a misleading title`() {
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, problem(T_PROGRESS, TITLE_REUSED)))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, problem(T_REUSED, TITLE_CONCURRENT)))
    }

    @Test
    fun `REQ-AND-015 type rate-limited is transient and honours Retry-After`() {
        assertEquals(UploadResult.Retry, classifyUploadStatus(429, false, problem(T_RATE, "Too Many Requests")))
        assertEquals(UploadResult.RetryAfter(90), classifyUploadStatus(429, false, problem(T_RATE), retryAfterSeconds = 90))
    }

    @Test
    fun `REQ-AND-015 generic conflict type is the ambiguous capped retry`() {
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, problem(P + "conflict", "Conflict")))
    }

    @Test
    fun `REQ-AND-015 about blank falls back to the title`() {
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, problem("about:blank", TITLE_REUSED)))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, problem(null, TITLE_REUSED)))
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, problem("about:blank", TITLE_CONCURRENT)))
    }

    @Test
    fun `REQ-AND-015 the title is not a fallback once a real type is present`() {
        // An unknown typed 409 is not treated as permanent just because of its title.
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, problem(P + "something-new", TITLE_REUSED)))
    }

    @Test
    fun `REQ-AND-015 only type and title are read from a body`() {
        val body = """{"type":"$T_DELETED","title":"t","status":409,"detail":"secret detail","traceId":"x"}"""
        assertEquals(ProblemInfo(T_DELETED, "t"), parseProblem(body))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, parseProblem(body)))
    }

    @Test
    fun `REQ-AND-015 an unreadable or unknown 409 body is retried with a cap, never forever`() {
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, null))
        assertNull(parseProblem("not json"))
        assertNull(parseProblem(null))
        assertEquals(ProblemInfo(null, null), parseProblem("""{"status":409}"""))
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, parseProblem("""{"status":409}""")))
    }

    @Test
    fun `REQ-AND-015 other statuses keep their meaning`() {
        assertEquals(UploadResult.Unauthorized, classifyUploadStatus(401, false))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(422, false))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(400, false))
    }

    @Test
    fun `REQ-AND-015 5xx 408 and 429 are transient and uncapped`() {
        for (code in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals("HTTP $code", UploadResult.Retry, classifyUploadStatus(code, false))
        }
    }

    @Test
    fun `REQ-AND-015 a Retry-After on a transient status is carried through`() {
        assertEquals(UploadResult.RetryAfter(120), classifyUploadStatus(429, false, retryAfterSeconds = 120))
        assertEquals(UploadResult.RetryAfter(30), classifyUploadStatus(503, false, retryAfterSeconds = 30))
        assertEquals(UploadResult.Retry, classifyUploadStatus(503, false, retryAfterSeconds = 0))
    }

    @Test
    fun `REQ-AND-015 a long outage never fails queued entries`() =
        runTest {
            val id = repo.add(entry())
            for (code in listOf(502, 503, 429, 408, 500)) {
                remote.uploadResult = { classifyUploadStatus(code, false) }
                repeat(10) { assertTrue(repo.syncOutbox().retry) }
            }
            val row = repo.observeAll().first().single { it.id == id }
            assertEquals(false, row.failed)
            assertEquals(1, repo.observePendingCount().first())
        }

    @Test
    fun `REQ-AND-015 the sync report carries the server's Retry-After`() =
        runTest {
            repo.add(entry())
            remote.uploadResult = { classifyUploadStatus(429, false, retryAfterSeconds = 900) }
            val report = repo.syncOutbox()
            assertTrue(report.retry)
            assertEquals(900L, report.retryAfterSeconds)
        }

    @Test
    fun `REQ-AND-015 a Retry-After outage then recovery uploads the entry`() =
        runTest {
            repo.add(entry())
            remote.uploadResult = { classifyUploadStatus(503, false, retryAfterSeconds = 60) }
            repeat(8) { repo.syncOutbox() }
            remote.uploadResult = { UploadResult.Created(expense("server-1", it.amountMinor)) }
            assertEquals(1, repo.syncOutbox().uploaded)
        }

    // ---- outbox behaviour

    @Test
    fun `REQ-AND-015 a permanent 409 marks the entry failed at once`() =
        runTest {
            val id = repo.add(entry())
            remote.uploadResult = { classifyUploadStatus(409, false, problem(T_REUSED)) }
            val report = repo.syncOutbox()
            assertEquals(1, report.rejected)
            assertEquals(false, report.retry)
            assertTrue(
                repo
                    .observeAll()
                    .first()
                    .single { it.id == id }
                    .failed,
            )
        }

    @Test
    fun `REQ-AND-015 a concurrent 409 is retried up to the cap then marked failed`() =
        runTest {
            val id = repo.add(entry())
            remote.uploadResult = { classifyUploadStatus(409, false, problem(T_PROGRESS)) }
            repeat(4) { assertTrue(repo.syncOutbox().retry) }
            val last = repo.syncOutbox()
            assertEquals(false, last.retry)
            assertEquals(1, last.rejected)
            assertTrue(
                repo
                    .observeAll()
                    .first()
                    .single { it.id == id }
                    .failed,
            )
        }

    @Test
    fun `REQ-AND-015 an unreachable network is never capped`() =
        runTest {
            repo.add(entry())
            remote.uploadResult = { UploadResult.Retry }
            repeat(12) { assertTrue(repo.syncOutbox().retry) }
            assertEquals(1, repo.observePendingCount().first())
        }

    // ---- legacy plaintext token (MINOR)

    @Test
    fun `REQ-AND-007 the legacy plaintext access token is deleted on upgrade and other settings survive`() =
        runTest {
            val file = tmp.newFile("settings.preferences_pb")
            val url = stringPreferencesKey("server_url")
            val token = stringPreferencesKey("access_token")

            // The pre-login build's store, then closed (one active DataStore per file).
            val oldScope = CoroutineScope(Job() + Dispatchers.Unconfined)
            PreferenceDataStoreFactory.create(scope = oldScope, produceFile = { file }).edit {
                it[url] = "https://masroof.example/"
                it[token] = "LEGACY-SECRET"
            }
            oldScope.cancel()
            // The upgraded app opens the same file with the migration.
            val upgraded =
                PreferenceDataStoreFactory.create(
                    migrations = listOf(legacyTokenMigration()),
                    scope = backgroundScope,
                    produceFile = { file },
                )
            val prefs = upgraded.data.first()
            assertNull(prefs[token])
            assertEquals("https://masroof.example/", prefs[url])
            assertTrue(!file.readBytes().decodeToString().contains("LEGACY-SECRET"))
        }
}
