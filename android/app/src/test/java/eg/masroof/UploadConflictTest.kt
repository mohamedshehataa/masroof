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
import eg.masroof.data.remote.UploadResult
import eg.masroof.data.remote.classifyUploadStatus
import eg.masroof.data.remote.problemTitle
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
private const val TITLE_CONCURRENT = "Conflict"
private const val TITLE_REUSED = "Idempotency key reused"

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
    fun `REQ-AND-015 409 concurrent request with the same key is retried with a cap`() {
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, TITLE_CONCURRENT))
    }

    @Test
    fun `REQ-AND-015 409 key reused with a different request is permanent`() {
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, TITLE_REUSED))
    }

    @Test
    fun `REQ-AND-015 409 the entry made with that key was deleted is permanent`() {
        // Same problem title as a reused key; the detail differs but is not parsed (message text is not a contract).
        val detail = "The expense created with this Idempotency-Key was deleted; use a new key to create it again"
        val body = """{"type":"about:blank","title":"$TITLE_REUSED","status":409,"detail":"$detail"}"""
        assertEquals(TITLE_REUSED, problemTitle(body))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(409, false, problemTitle(body)))
    }

    @Test
    fun `REQ-AND-015 an unreadable or unknown 409 body is retried with a cap, never forever`() {
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(409, false, null))
        assertNull(problemTitle("not json"))
        assertNull(problemTitle(null))
        assertNull(problemTitle("""{"status":409}"""))
    }

    @Test
    fun `REQ-AND-015 other statuses keep their meaning`() {
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(429, false))
        assertEquals(UploadResult.RetryLimited, classifyUploadStatus(503, false))
        assertEquals(UploadResult.Unauthorized, classifyUploadStatus(401, false))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(422, false))
        assertEquals(UploadResult.Rejected, classifyUploadStatus(400, false))
    }

    // ---- outbox behaviour

    @Test
    fun `REQ-AND-015 a permanent 409 marks the entry failed at once`() =
        runTest {
            val id = repo.add(entry())
            remote.uploadResult = { classifyUploadStatus(409, false, TITLE_REUSED) }
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
            remote.uploadResult = { classifyUploadStatus(409, false, TITLE_CONCURRENT) }
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
