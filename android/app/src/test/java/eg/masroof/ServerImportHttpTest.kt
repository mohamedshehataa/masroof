package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.Money
import eg.masroof.core.analytics.RoomAnalyticsEngine
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.NewExpense
import eg.masroof.data.auth.ApiAuthRemote
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.auth.SessionState
import eg.masroof.data.budgets.BudgetDraft
import eg.masroof.data.budgets.RoomBudgetsRepository
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.data.insights.LocalInsightsRepository
import eg.masroof.data.remote.ApiRemoteSource
import eg.masroof.data.remote.newAuthClient
import eg.masroof.data.remote.newBareClient
import eg.masroof.data.serverimport.ApiServerImportSource
import eg.masroof.data.serverimport.DefaultServerForgetter
import eg.masroof.data.serverimport.ImportFailureKind
import eg.masroof.data.serverimport.ImportResult
import eg.masroof.data.serverimport.ServerImporter
import eg.masroof.ingest.SessionUploadCheck
import eg.masroof.ingest.SmsUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val HOUSEHOLD = "22222222-2222-2222-2222-222222222222"

private fun uuid(
    ns: Long,
    n: Int,
) = UUID(ns, n.toLong()).toString()

private val ME_ID = uuid(3, 1)

/** The real client stack (Retrofit, bearer, refresh, generated models) against a fake server: STORY-182 end to end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerImportHttpTest {
    private lateinit var w: AnswerWorld
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<String>()
    private val importStore = MemoryImportStore()
    private val expensePages = AtomicInteger()

    /** Every request of any client, recorded before it leaves (REQ-SEC-065). */
    private val recorder =
        Interceptor { chain ->
            val path = chain.request().url.encodedPath
            // a dropped connection: the request never gets an answer
            if (path.endsWith("/api/expenses") && attempts.getAndIncrement() == dropAt) throw java.io.IOException("connection reset")
            chain.proceed(chain.request()).also { seen += path }
        }
    private val attempts = AtomicInteger()

    @Volatile private var dropAt: Int? = null

    // scripted behaviour
    @Volatile private var failPage: Int? = null

    @Volatile private var failMode = 503

    @Volatile private var pageBody: ((Int, Int) -> String)? = null

    @Volatile private var refreshStatus = 200

    @Volatile private var categoriesOverride: MockResponse? = null
    private var liveCount = 1_240
    private val store = FakeSessionStore(LoadedSession.Found(session("access-1", "refresh-1", ME_ID)))

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun expenseJson(
        n: Int,
        amount: String = "${100 + n}",
        type: String = "EXPENSE",
        notes: String? = null,
        deleted: Boolean = false,
        category: String? = null,
    ) = "{\"id\":\"${uuid(5, n)}\",\"householdId\":\"$HOUSEHOLD\",\"type\":\"$type\"," +
        "\"amount\":{\"amountMinor\":$amount,\"currency\":\"EGP\"},\"occurredAt\":\"2026-10-01T10:00:00Z\"," +
        "\"paymentMethod\":\"CARD\",\"status\":\"CONFIRMED\",\"confidence\":0.9,\"tags\":[],\"isRecurring\":false," +
        "\"splitMode\":\"NONE\",\"items\":[],\"sources\":[],\"splits\":[],\"createdAt\":\"2026-10-01T10:00:01Z\"," +
        "\"updatedAt\":\"2026-10-01T10:00:01Z\"" +
        (if (notes != null) ",\"notes\":\"$notes\"" else "") +
        (if (deleted) ",\"deletedAt\":\"2026-10-02T10:00:00Z\"" else "") +
        (if (category != null) ",\"categoryId\":\"$category\"" else "") + "}"

    /** 1,240 live rows, 20 soft-deleted rows mixed in; page [p] of 200. */
    private fun defaultPage(
        p: Int,
        limit: Int,
    ): String {
        val all = (0 until liveCount).map { expenseJson(it) } + (0 until 20).map { expenseJson(10_000 + it, deleted = true) }
        val from = p * limit
        val to = minOf(all.size, from + limit)
        val next = if (to < all.size) "\"${p + 1}\"" else "null"
        return "{\"items\":[${all.subList(from, to).joinToString(",")}],\"nextCursor\":$next}"
    }

    @Before
    fun setUp() {
        w = AnswerWorld(ApplicationProvider.getApplicationContext<Context>())
        eg.masroof.data.db.DefaultSeed
            .seed(w.db.openHelper.writableDatabase)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path.endsWith("/auth/refresh") ->
                            if (refreshStatus ==
                                200
                            ) {
                                json(tokens())
                            } else {
                                MockResponse.Builder().code(refreshStatus).build()
                            }
                        path.endsWith("/auth/logout") -> MockResponse.Builder().code(204).build()
                        refreshStatus == 401 && request.headers["Authorization"] == "Bearer access-1" ->
                            MockResponse
                                .Builder()
                                .code(
                                    401,
                                ).build()
                        path.endsWith("/api/expenses") -> expenses(request)
                        path.endsWith("/api/categories") -> categoriesOverride ?: json(categories())
                        path.endsWith("/api/accounts") -> json(accounts())
                        path.endsWith("/api/members") -> json(members())
                        path.endsWith("/api/budgets") -> json(budgets())
                        path.endsWith(
                            "/api/settings",
                        ) -> json("{\"monthStartDay\":25,\"countAtmWithdrawalsAsSpending\":true,\"whatsappMode\":\"X\"}")
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        w.close()
    }

    private fun tokens() =
        "{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\",\"expiresIn\":900,\"tokenType\":\"Bearer\",\"member\":" +
            "{\"id\":\"$ME_ID\",\"householdId\":\"$HOUSEHOLD\",\"displayName\":\"Mohamed\",\"role\":\"ADMIN\"}}"

    private fun expenses(request: RecordedRequest): MockResponse {
        val p = request.url.queryParameter("cursor")?.toInt() ?: 0
        val limit = request.url.queryParameter("limit")!!.toInt()
        val n = expensePages.getAndIncrement()
        if (failPage == n) return MockResponse.Builder().code(failMode).build()
        return json((pageBody ?: ::defaultPage)(p, limit))
    }

    private fun categories() =
        "{\"items\":[" +
            (0 until 31).joinToString(",") {
                val name = if (it < DEFAULT_CATEGORY_NAMES.size) DEFAULT_CATEGORY_NAMES[it] else "Custom $it"
                "{\"id\":\"${uuid(1, it)}\",\"householdId\":\"$HOUSEHOLD\",\"nameEn\":\"$name\",\"nameAr\":\"x\",\"isEssential\":false," +
                    "\"sortOrder\":$it,\"archived\":false}"
            } + "]}"

    private fun accounts() =
        "{\"items\":[" +
            (1..3).joinToString(",") {
                "{\"id\":\"${uuid(
                    2,
                    it,
                )}\",\"memberId\":\"$ME_ID\",\"type\":\"CASH\",\"currency\":\"EGP\",\"isShared\":false,\"nickname\":\"A$it\"}"
            } + "]}"

    private fun members() =
        "[{\"id\":\"$ME_ID\",\"householdId\":\"$HOUSEHOLD\",\"displayName\":\"Mohamed\",\"role\":\"ADMIN\"}," +
            "{\"id\":\"${uuid(3, 2)}\",\"householdId\":\"$HOUSEHOLD\",\"displayName\":\"Mona\",\"role\":\"MEMBER\"}]"

    private fun budgets() =
        "{\"items\":[" +
            (1..4).joinToString(",") {
                "{\"id\":\"${uuid(4, it)}\",\"householdId\":\"$HOUSEHOLD\",\"categoryId\":null,\"period\":\"MONTHLY\"," +
                    "\"amount\":{\"amountMinor\":${it * 1000},\"currency\":\"EGP\"},\"rollover\":false,\"alertThresholds\":[80]}"
            } + "]}"

    private lateinit var manager: SessionManager
    private lateinit var remote: ApiRemoteSource

    private fun build() {
        runBlocking { w.settings.update { it.copy(serverUrl = server.url("/").toString()) } }
        manager =
            SessionManager(store, ApiAuthRemote(newAuthClient().newBuilder().addInterceptor(recorder).build()), w.settings, Dispatchers.IO)
        remote = ApiRemoteSource(w.settings, newBareClient().newBuilder().addInterceptor(recorder).build(), manager)
    }

    private fun runBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    private fun importer() =
        ServerImporter(
            w.db,
            ApiServerImportSource(remote),
            manager,
            w.settings,
            w.repo,
            w.prefs,
            DefaultServerForgetter(manager, w.settings, FakeSessionKeystore()),
            EraseLock(),
            LocalDataSignal(),
            w.clock,
            Dispatchers.IO,
            importStore,
        )

    @Test
    fun `REQ-OPS-175 REQ-SEC-065 imports 1,240 expenses over the real client in 7 pages, logs out once and forgets`() =
        runTest {
            build()
            assertEquals(ImportResult.Completed(1_240, 0), importer().run {})
            assertEquals(1_240, w.db.expenses().count())
            assertEquals(7, expensePages.get())
            assertEquals(1, seen.count { it.endsWith("/auth/logout") })
            assertEquals(
                31,
                w.db
                    .lookups()
                    .allCategories()
                    .size,
            )
            assertEquals(
                4,
                w.db
                    .budgets()
                    .all()
                    .size,
            )
            assertEquals(25, w.settings.current().monthStartDay)
            assertEquals("", w.settings.current().serverUrl)
            assertTrue(store.stored is LoadedSession.Empty)
        }

    @Test
    fun `REQ-OPS-175 a 5xx or a dropped connection on page 4 stops the run and the next run ends at exactly 1,240`() =
        runTest {
            build()
            for (mode in listOf(503, 0)) {
                failPage = if (mode == 0) null else expensePages.get() + 3
                failMode = mode
                dropAt = if (mode == 0) attempts.get() + 3 else null
                assertEquals(
                    ImportResult.Failed(if (mode == 0) ImportFailureKind.NETWORK else ImportFailureKind.SERVER),
                    importer().run {},
                )
                assertEquals(0, w.db.expenses().count())
                assertTrue("credentials kept", store.stored is LoadedSession.Found)
            }
            failPage = null
            dropAt = null
            assertEquals(ImportResult.Completed(1_240, 0), importer().run {})
            assertEquals(1_240, w.db.expenses().count())
        }

    @Test
    fun `REQ-OPS-175 a non-integer amount, an unknown enum and an unknown category are skipped and counted`() =
        runTest {
            build()
            pageBody = { _, _ ->
                "{\"items\":[${expenseJson(1)},${expenseJson(2, amount = "12.5")},${expenseJson(3, type = "BOGUS")}," +
                    "${expenseJson(4, category = uuid(9, 9))},${expenseJson(5)}],\"nextCursor\":null}"
            }
            assertEquals(ImportResult.Completed(2, 3), importer().run {})
            assertEquals(2, w.db.expenses().count())
        }

    @Test
    fun `REQ-SEC-065 an oversized string, an oversized page, a bad cursor or a non-object body stop the run and store nothing`() =
        runTest {
            build()
            val bodies =
                listOf<(Int, Int) -> String>(
                    { _, _ -> "{\"items\":[${expenseJson(1)},${expenseJson(2, notes = "x".repeat(501))}],\"nextCursor\":null}" },
                    { _, _ -> "{\"items\":[${(0 until 201).joinToString(",") { expenseJson(it) }}],\"nextCursor\":null}" },
                    { _, _ -> "{\"items\":[${expenseJson(1)}],\"nextCursor\":7}" },
                    { _, _ -> "[1,2,3]" },
                    { _, _ -> "not json" },
                    { _, _ -> "{\"items\":[${expenseJson(1)}],\"nextCursor\":\"1\"}" },
                )
            for ((idx, b) in bodies.withIndex()) {
                pageBody = b
                assertEquals("body $idx", ImportResult.Failed(ImportFailureKind.UNEXPECTED_DATA), importer().run {})
                assertEquals(0, w.db.expenses().count())
            }
            assertTrue(store.stored is LoadedSession.Found)
            assertEquals(0, seen.count { it.endsWith("/auth/logout") })
        }

    @Test
    fun `REQ-SEC-065 a response body above the cap stops the run as unexpected data`() =
        runTest {
            build()
            categoriesOverride = json("{\"items\":[],\"pad\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}")
            assertEquals(ImportResult.Failed(ImportFailureKind.UNEXPECTED_DATA), importer().run {})
            assertEquals(0, w.db.expenses().count())
        }

    @Test
    fun `REQ-SEC-065 a redirect is never followed (the bearer token would go with it)`() =
        runTest {
            build()
            categoriesOverride =
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", "https://evil.example/x")
                    .build()
            assertEquals(ImportResult.Failed(ImportFailureKind.SERVER), importer().run {})
            assertTrue(seen.none { it.contains("evil") })
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `REQ-SEC-065 a lookup list above the cap stops the run`() =
        runTest {
            build()
            val many =
                (0 until 2_001).joinToString(",") { i ->
                    "{\"id\":\"${uuid(1, i)}\",\"householdId\":\"$HOUSEHOLD\",\"nameEn\":\"c$i\",\"nameAr\":\"x\",\"isEssential\":false," +
                        "\"sortOrder\":$i,\"archived\":false,\"color\":\"red\"}"
                }
            categoriesOverride = json("{\"items\":[$many]}")
            assertEquals(ImportResult.Failed(ImportFailureKind.UNEXPECTED_DATA), importer().run {})
        }

    @Test
    fun `REQ-OPS-174 a refresh token the server rejects ends the run with Sign in again and leaves local data alone`() =
        runTest {
            build()
            w.db.expenses().insert(answerEntry("mine", source = "MANUAL"))
            refreshStatus = 401
            assertEquals(ImportResult.Failed(ImportFailureKind.SESSION), importer().run {})
            assertTrue(manager.state.value is SessionState.SignedOut)
            assertEquals(1, w.db.expenses().count())
            assertEquals(0, expensePages.get())
        }

    @Test
    fun `REQ-SEC-065 after a completed import no HTTP request of any kind is made over start, capture, add, Insights and Budgets`() =
        runTest {
            build()
            assertEquals(ImportResult.Completed(1_240, 0), importer().run {})
            val requestsBefore = server.requestCount
            seen.clear()
            // app start after the import: a fresh manager over the (now empty) store and settings, on the recording clients
            val manager2 =
                SessionManager(
                    store,
                    ApiAuthRemote(newAuthClient().newBuilder().addInterceptor(recorder).build()),
                    w.settings,
                    Dispatchers.IO,
                )
            val remote2 = ApiRemoteSource(w.settings, newBareClient().newBuilder().addInterceptor(recorder).build(), manager2)
            manager2.restore()
            assertNull2(manager2.freshAccessToken())
            // capture: a queued record is not sent without a session
            w.db.parsedOutbox().insert(
                eg.masroof.data.db.ParsedOutboxEntity(
                    "p1",
                    "SMS",
                    "PURCHASE",
                    5_000,
                    "EGP",
                    w.clock.millis(),
                    "1111",
                    "CIB",
                    null,
                    null,
                    null,
                    createdAtMillis = w.clock.millis(),
                ),
            )
            val uploader =
                SmsUploader(
                    w.db,
                    remote2,
                    object : eg.masroof.ingest.DeviceIdProvider {
                        override fun get() = "11111111-1111-1111-1111-111111111111"
                    },
                    w.clock,
                    MemoryGate(),
                    upload = SessionUploadCheck(manager2),
                )
            uploader.uploadPending()
            // add
            var n = 0
            val ids =
                object : IdFactory {
                    override fun next() = "id-${n++}"
                }
            DefaultExpenseRepository(w.db, w.clock, ids, LocalDataSignal())
                .add(
                    NewExpense(
                        Money(1_000),
                        eg.masroof.core.CairoTime
                            .today(w.clock.instant()),
                        null,
                        null,
                        "x",
                    ),
                )
            // Insights
            LocalInsightsRepository(RoomAnalyticsEngine(w.db, w.settings, w.clock, Dispatchers.IO), w.clock).refresh(InsightsPeriod.CURRENT)
            // Budgets
            val budgets = RoomBudgetsRepository(w.db, w.clock, ids, EraseLock(), Dispatchers.IO)
            budgets.create(BudgetDraft("cat-groceries", 50_000, rollover = false))
            budgets.observe().first()
            // even a direct call of the frozen client cannot build a request: the server URL is gone
            // CSV export, Settings (its view model restores the session and saves) and the review list: Room only
            eg.masroof.data.export
                .CsvExporter(w.db)
                .export(
                    eg.masroof.data.export
                        .ExportRange(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31)),
                    java.io.ByteArrayOutputStream(),
                )
            val settingsVm =
                eg.masroof.ui.viewmodel
                    .SettingsViewModel(w.settings, manager2, TestAuth.eraser())
            settingsVm.state.first { it.showServer == false }
            settingsVm.save()
            // even with a server URL typed in again, nothing is sent without a session
            w.settings.update { it.copy(serverUrl = server.url("/").toString()) }
            runCatching { remote2.fetchCategories() }
            runCatching { remote2.fetchAccounts() }
            assertEquals(emptyList<String>(), seen.toList())
            assertEquals(requestsBefore, server.requestCount)
        }

    private fun assertNull2(v: Any?) = assertEquals(null, v)
}
