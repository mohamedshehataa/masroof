package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.DuplicateMatcher
import eg.masroof.data.answers.EntrySources
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.DefaultDuplicateRepository
import eg.masroof.data.edit.DuplicateCard
import eg.masroof.data.edit.MergeResult
import eg.masroof.data.serverimport.ServerExpenseRow
import eg.masroof.data.serverimport.ServerImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import java.time.Instant

private val T0: Long = Instant.parse("2026-11-06T21:04:10Z").toEpochMilli()
private const val MIN = 60_000L

/** REQ-DEDUP-066: a server row that looks like a local one becomes the STORY-173 "Possible duplicate" card, never a silent merge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerImportDuplicateTest {
    private lateinit var w: AnswerWorld
    private val source = FakeImportSource(rows = emptyList())
    private val importStore = MemoryImportStore()
    private lateinit var repo: DefaultDuplicateRepository

    @Before
    fun setUp() {
        w = AnswerWorld(ApplicationProvider.getApplicationContext<Context>())
        repo = DefaultDuplicateRepository(w.db, w.prefs, w.clock, EraseLock(), LocalDataSignal())
        source.categories = emptyList()
        source.budgets = emptyList()
    }

    @After
    fun tearDown() = w.close()

    private suspend fun local(
        id: String = "local",
        at: Long = T0,
        amount: Long = 100,
        manual: Boolean = true,
        serverId: String? = null,
        type: String = "EXPENSE",
    ) {
        val e =
            if (manual) {
                manualEntry(id, amount = amount, at = at, timeKnown = true, type = type)
            } else {
                captureEntry(id, amount = amount, at = at, type = type)
            }
        w.db.expenses().insert(e.copy(serverId = serverId))
        w.db.sources().insert(if (manual) manualSource(id) else captureSource(id))
    }

    private suspend fun import(vararg rows: eg.masroof.data.serverimport.ServerExpense) {
        source.rows = rows.map { ServerExpenseRow.Valid(it) }
        val session = FakeSessionStore(LoadedSession.Found(session("a", "r", SRV_ME)))
        val manager = SessionManager(session, FakeAuthRemote(), w.settings, Dispatchers.Unconfined)
        ServerImporter(
            w.db,
            source,
            manager,
            w.settings,
            w.repo,
            w.prefs,
            RecordingForgetter(),
            EraseLock(),
            LocalDataSignal(),
            w.clock,
            Dispatchers.Unconfined,
            importStore,
        ).run {}
    }

    private suspend fun cards(): List<DuplicateCard> = settled(repo.observeCards())

    /** Room emits from its own thread: read one emission in real time after the writes are done. */
    private suspend fun <T> settled(flow: Flow<T>): T = withContext(Dispatchers.Default) { withTimeout(10_000) { flow.first() } }

    private suspend fun row(id: String): ExpenseEntity = w.db.expenses().find(id)!!

    @Test
    fun `REQ-DEDUP-066 a server row 80 seconds from a local entry is PENDING_REVIEW with a Merge or Keep both card, nothing merges`() =
        runTest {
            local()
            import(serverExpense("srv-1", amount = 100, at = T0 + 80_000))
            assertEquals("PENDING_REVIEW", row("srv-1").status)
            val card = cards().single()
            assertEquals("local|srv-1", card.key)
            // the local entry is the typed side, the imported row the other side
            assertEquals("local", card.manual.id)
            assertEquals("srv-1", card.capture.id)
            // nothing is merged on its own
            assertNull(row("local").deletedAtMillis)
            assertNull(row("srv-1").deletedAtMillis)
            assertEquals(2, w.db.expenses().count())
        }

    @Test
    fun `REQ-DEDUP-066 exactly 2 minutes apart is a pair and 2 minutes 1 second apart is not`() =
        runTest {
            local()
            import(
                serverExpense("edge-in", amount = 100, at = T0 + 2 * MIN),
                serverExpense(
                    "edge-out",
                    amount = 100,
                    at =
                        T0 - 2 * MIN - 1_000,
                ),
            )
            assertEquals("PENDING_REVIEW", row("edge-in").status)
            assertEquals("CONFIRMED", row("edge-out").status)
            assertEquals(listOf("local|edge-in"), cards().map { it.key })
        }

    @Test
    fun `REQ-DEDUP-066 2 minutes 1 second later and an earlier server row follow the same edge`() =
        runTest {
            local()
            import(serverExpense("late", amount = 100, at = T0 + 2 * MIN + 1_000), serverExpense("early", amount = 100, at = T0 - 2 * MIN))
            assertEquals("CONFIRMED", row("late").status)
            assertEquals("PENDING_REVIEW", row("early").status)
            assertEquals(listOf("local|early"), cards().map { it.key })
        }

    @Test
    fun `REQ-DEDUP-066 a different currency, amount or type gives no card and the server row keeps its status`() =
        runTest {
            local()
            import(
                serverExpense("usd", amount = 100, at = T0 + MIN, currency = "USD"),
                serverExpense("amount", amount = 101, at = T0 + MIN),
                serverExpense("income", amount = 100, at = T0 + MIN, type = "INCOME"),
            )
            assertEquals(listOf("CONFIRMED", "CONFIRMED", "CONFIRMED"), listOf("usd", "amount", "income").map { row(it).status })
            assertTrue(cards().isEmpty())
        }

    @Test
    fun `REQ-DEDUP-066 a converted capture without a server id is a candidate too`() =
        runTest {
            local(manual = false)
            import(serverExpense("srv-1", amount = 100, at = T0 + MIN))
            assertEquals("PENDING_REVIEW", row("srv-1").status)
            assertEquals(listOf("local|srv-1"), cards().map { it.key })
        }

    @Test
    fun `REQ-DEDUP-066 a local row that already has a server id is not a candidate`() =
        runTest {
            local(serverId = "other-server-id")
            import(serverExpense("srv-1", amount = 100, at = T0 + MIN))
            assertEquals("CONFIRMED", row("srv-1").status)
            assertTrue(cards().isEmpty())
        }

    @Test
    fun `REQ-DEDUP-066 a twin older than 100 days is still PENDING_REVIEW with a card (no age limit)`() =
        runTest {
            val old = Instant.parse("2026-06-01T21:04:10Z").toEpochMilli()
            local(at = old)
            import(serverExpense("srv-old", amount = 100, at = old + 80_000))
            assertEquals("PENDING_REVIEW", row("srv-old").status)
            assertEquals(listOf("local|srv-old"), cards().map { it.key })
        }

    @Test
    fun `REQ-DEDUP-066 REQ-EXP-065 an imported row never touches a linked local row and is not paired with it`() =
        runTest {
            local()
            val before = row("local")
            w.prefs.edit {
                it.copy(
                    links =
                        it.links +
                            eg.masroof.data.answers
                                .EntryLink(eg.masroof.data.answers.LinkKind.REFUND, "local", "somewhere-else"),
                )
            }
            import(serverExpense("srv-1", amount = 100, at = T0 + MIN))
            assertEquals(before, row("local"))
            assertEquals("CONFIRMED", row("srv-1").status)
            assertTrue(cards().isEmpty())
        }

    @Test
    fun `REQ-DEDUP-066 a later run does not refresh an imported row that a link now owns`() =
        runTest {
            import(serverExpense("srv-1", amount = 100, at = T0))
            val before = row("srv-1")
            w.prefs.edit {
                it.copy(
                    links =
                        it.links +
                            eg.masroof.data.answers
                                .EntryLink(eg.masroof.data.answers.LinkKind.REFUND, "srv-1", "somewhere-else"),
                )
            }
            import(serverExpense("srv-1", amount = 999, at = T0, merchant = "Changed"))
            assertEquals(before, row("srv-1"))
        }

    @Test
    fun `REQ-DEDUP-066 Merge keeps the server row (serverId, time, amount), moves the sources and settles its status, and Undo restores`() =
        runTest {
            local()
            import(serverExpense("srv-1", amount = 100, at = T0 + 80_000))
            val merged = repo.merge("local", "srv-1")
            assertTrue(merged is MergeResult.Merged)
            val survivor = row("srv-1")
            assertEquals("srv-1", survivor.serverId)
            assertEquals("CONFIRMED", survivor.status)
            assertEquals(T0 + 80_000, survivor.occurredAtMillis)
            assertNotNull(row("local").deletedAtMillis)
            assertEquals(
                setOf("SERVER_IMPORT", "MANUAL"),
                w.db
                    .sources()
                    .forExpense("srv-1")
                    .map { it.channel }
                    .toSet(),
            )
            assertTrue(cards().isEmpty())
            assertTrue(repo.undo((merged as MergeResult.Merged).undo))
            assertEquals("PENDING_REVIEW", row("srv-1").status)
            assertNull(row("local").deletedAtMillis)
            assertEquals(listOf("local|srv-1"), cards().map { it.key })
        }

    @Test
    fun `REQ-DEDUP-066 Keep both removes the card, changes no row, and a repeat import does not ask again`() =
        runTest {
            local()
            import(serverExpense("srv-1", amount = 100, at = T0 + MIN))
            val before = listOf(row("local"), row("srv-1"))
            repo.keepBoth("local", "srv-1")
            assertTrue(cards().isEmpty())
            import(serverExpense("srv-1", amount = 100, at = T0 + MIN))
            assertEquals(before, listOf(row("local"), row("srv-1")))
            assertTrue(cards().isEmpty())
        }

    @Test
    fun `REQ-DEDUP-063 the typed entry and capture rule of STORY-173 is unchanged next to a server row`() =
        runTest {
            val m = manualEntry("m", amount = 5_000, at = T0, timeKnown = true)
            val c = captureEntry("c", amount = 5_000, at = T0 + MIN)
            w.db.expenses().insert(m)
            w.db.expenses().insert(c)
            w.db.sources().insert(manualSource("m"))
            w.db.sources().insert(captureSource("c"))
            import(serverExpense("srv-9", amount = 7_777, at = T0))
            assertEquals(listOf("m|c"), cards().map { it.key })
        }

    @Test
    fun `REQ-DEDUP-066 the matcher rule is pure and exact at the two minute edge`() {
        val local = manualEntry("l", amount = 100, at = T0, timeKnown = true)
        val server =
            captureEntry(
                "s",
                amount = 100,
                at = T0 + 2 * MIN,
            ).copy(source = "SERVER_IMPORT", serverId = "s", status = "PENDING_REVIEW")
        val ls = EntrySources(setOf("MANUAL"))
        val ss = EntrySources(setOf("SERVER_IMPORT"))
        assertTrue(DuplicateMatcher.matchesServer(local, ls, server, ss, emptySet()))
        assertFalse(DuplicateMatcher.matchesServer(local, ls, server.copy(occurredAtMillis = T0 + 2 * MIN + 1), ss, emptySet()))
        assertFalse(DuplicateMatcher.matchesServer(local, ls, server, ss, setOf("l")))
        assertFalse(DuplicateMatcher.matchesServer(local, ls, server.copy(status = "CONFIRMED"), ss, emptySet()))
        assertFalse(DuplicateMatcher.matchesServer(local.copy(deletedAtMillis = 1), ls, server, ss, emptySet()))
    }
}
