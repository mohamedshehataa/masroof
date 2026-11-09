package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.auth.LoadedSession
import eg.masroof.data.auth.SessionManager
import eg.masroof.data.db.ME_MEMBER_ID
import eg.masroof.data.serverimport.DefaultServerForgetter
import eg.masroof.data.serverimport.ImportFailureKind
import eg.masroof.data.serverimport.ImportProgress
import eg.masroof.data.serverimport.ImportResult
import eg.masroof.data.serverimport.ImportStage
import eg.masroof.data.serverimport.ServerExpenseRow
import eg.masroof.data.serverimport.ServerForgetter
import eg.masroof.data.serverimport.ServerImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The importer on a scripted fake server (no network): REQ-OPS-175, REQ-SEC-065. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass") // one world (db, session, fake server) shared by every REQ of the importer
class ServerImportTest {
    private lateinit var w: AnswerWorld
    private val store = FakeSessionStore(LoadedSession.Found(session("access-1", "refresh-1", SRV_ME)))
    private val auth = FakeAuthRemote()
    private val keystore = FakeSessionKeystore()
    private val source = FakeImportSource()
    private val importStore = MemoryImportStore()
    private lateinit var manager: SessionManager
    private val progress = mutableListOf<ImportProgress>()

    @Before
    fun setUp() {
        w = AnswerWorld(ApplicationProvider.getApplicationContext<Context>())
        eg.masroof.data.db.DefaultSeed
            .seed(w.db.openHelper.writableDatabase)
        manager = SessionManager(store, auth, w.settings, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = w.close()

    private fun importer(forgetter: ServerForgetter = DefaultServerForgetter(manager, w.settings, keystore)) =
        ServerImporter(
            w.db,
            source,
            manager,
            w.settings,
            w.repo,
            w.prefs,
            forgetter,
            EraseLock(),
            LocalDataSignal(),
            w.clock,
            Dispatchers.Unconfined,
            importStore,
        )

    private suspend fun run(importer: ServerImporter = importer()) = importer.run { progress += it }

    private suspend fun count() = w.db.expenses().count()

    @Test
    fun `REQ-OPS-175 imports every live server expense with its fields and a SERVER_IMPORT source`() =
        runTest {
            val result = run()
            assertEquals(ImportResult.Completed(1_240, 0), result)
            assertEquals(1_240, count())
            assertEquals(
                1_240,
                w.db
                    .sources()
                    .all()
                    .size,
            )
            assertTrue(
                w.db
                    .sources()
                    .all()
                    .all { it.channel == "SERVER_IMPORT" },
            )
            // soft-deleted server rows are not imported
            assertNull(w.db.expenses().findByServerId("srv-del-00"))
            val row = w.db.expenses().findByServerId("srv-exp-0001")!!
            assertEquals(101L, row.amountMinor)
            assertEquals("EGP", row.currency)
            assertEquals("Spinneys", row.merchant)
            assertEquals("weekly", row.notes)
            assertEquals("SERVER_IMPORT", row.source)
            assertEquals("srv-exp-0001", row.serverId)
            assertEquals(SERVER_BASE + 7 * 60_000, row.occurredAtMillis)
            assertEquals("CONFIRMED", row.status)
            assertFalse(row.excludedFromTotals)
            assertEquals("srv-acc-2", row.accountId)
            // every server type is kept as it is
            val types =
                w.db
                    .expenses()
                    .all()
                    .map { it.type }
                    .toSet()
            assertEquals(setOf("EXPENSE", "INCOME", "REFUND", "TRANSFER", "CASH_WITHDRAWAL"), types)
            // PENDING_REVIEW stays PENDING_REVIEW (i % 40 == 0: 31 rows)
            assertEquals(
                31,
                w.db
                    .expenses()
                    .all()
                    .count { it.status == "PENDING_REVIEW" },
            )
        }

    @Test
    fun `REQ-OPS-175 shows progress Imported 600 of 1,240 while it saves`() =
        runTest {
            run()
            assertTrue(progress.contains(ImportProgress(ImportStage.SAVING, 600, 1_240)))
            assertEquals(ImportProgress(ImportStage.SAVING, 1_240, 1_240), progress.last())
            // the download shows a growing count, never a total (the server sends none)
            assertTrue(progress.filter { it.stage == ImportStage.DOWNLOADING }.all { it.total == null })
        }

    @Test
    fun `REQ-OPS-175 categories accounts members budgets and settings are copied by server id`() =
        runTest {
            run()
            val categories = w.db.lookups().allCategories()
            // 29 defaults are matched by name (not added twice); the 2 custom ones keep the server id
            assertEquals(31, categories.size)
            assertEquals("srv-cat-29", categories.single { it.nameEn == "Gym" }.id)
            assertFalse(categories.single { it.nameEn == "Gym" }.system)
            assertEquals(
                "cat-groceries",
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0031")!!
                    .categoryId,
            )
            // srv-exp-0001 has category srv-cat-1 = "Dining out" = the phone's default
            assertEquals(
                "cat-dining",
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0001")!!
                    .categoryId,
            )
            assertEquals(
                "srv-cat-29",
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0029")!!
                    .categoryId,
            )
            assertNull(
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0010")!!
                    .categoryId,
            )
            assertEquals(
                3,
                w.db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .size,
            )
            // "Me" takes the signed-in member's name; the other member is added; paid-by follows
            val members =
                w.db
                    .lookups()
                    .observeMembers()
                    .first()
            assertEquals(setOf("Mohamed", "Mona"), members.map { it.displayName }.toSet())
            assertEquals(
                ME_MEMBER_ID,
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0002")!!
                    .paidByMemberId,
            )
            assertEquals(
                SRV_OTHER,
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0001")!!
                    .paidByMemberId,
            )
            val budgets = w.db.budgets().all()
            assertEquals(listOf("srv-bud-1", "srv-bud-2", "srv-bud-3", "srv-bud-4"), budgets.map { it.id }.sorted())
            assertNull(budgets.single { it.id == "srv-bud-1" }.categoryId)
            assertEquals("cat-groceries", budgets.single { it.id == "srv-bud-2" }.categoryId)
            assertEquals("50,80,100", budgets.single { it.id == "srv-bud-3" }.alertThresholds)
            assertEquals("CUSTOM", budgets.single { it.id == "srv-bud-4" }.period)
            assertEquals(25, w.settings.current().monthStartDay)
            assertTrue(w.settings.current().includeCashWithdrawals)
        }

    @Test
    fun `REQ-OPS-175 a server duplicate budget and an unsupported period are stored for the Budgets screen to show`() =
        runTest {
            source.budgets =
                listOf(
                    eg.masroof.data.serverimport
                        .ServerBudget("b1", "srv-cat-0", "MONTHLY", 100, "EGP", false, listOf(80)),
                    eg.masroof.data.serverimport
                        .ServerBudget("b2", "srv-cat-0", "MONTHLY", 200, "EGP", false, listOf(80)),
                    eg.masroof.data.serverimport
                        .ServerBudget("b3", null, "MONTHLY", 300, "USD", false, listOf(80)),
                    eg.masroof.data.serverimport
                        .ServerBudget("b4", "srv-cat-404", "MONTHLY", 300, "EGP", false, listOf(80)),
                    eg.masroof.data.serverimport
                        .ServerBudget("b5", null, "WEEKLY", 0, "EGP", false, listOf(80)),
                )
            val result = run()
            // b4 (unknown category) and b5 (amount 0) break a limit: skipped and counted with the entries
            assertEquals(ImportResult.Completed(1_240, 0, 2), result)
            assertEquals(
                listOf("b1", "b2", "b3"),
                w.db
                    .budgets()
                    .all()
                    .map { it.id }
                    .sorted(),
            )
            assertEquals(
                "USD",
                w.db
                    .budgets()
                    .byId("b3")!!
                    .currency,
            )
        }

    @Test
    fun `REQ-OPS-175 a second run duplicates nothing`() =
        runTest {
            val keep = RecordingForgetter()
            assertEquals(ImportResult.Completed(1_240, 0), run(importer(keep)))
            assertEquals(ImportResult.Completed(1_240, 0), run(importer(keep)))
            assertEquals(1_240, count())
            assertEquals(
                1_240,
                w.db
                    .sources()
                    .all()
                    .size,
            )
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
            assertEquals(
                2,
                w.db
                    .lookups()
                    .observeMembers()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-OPS-175 a category renamed on the phone between runs is not mapped again and nothing moves`() =
        runTest {
            val keep = RecordingForgetter()
            // the first run is killed while saving (after the lookups and 600 rows were written)
            try {
                importer(keep).run { p -> if (p.stage == ImportStage.SAVING && p.done == 600) throw CancellationException("killed") }
            } catch (_: CancellationException) {
                // the process died
            }
            val groceries =
                w.db
                    .lookups()
                    .allCategories()
                    .single { it.id == "cat-groceries" }
            w.db.lookups().upsertCategories(listOf(groceries.copy(nameEn = "Supermarket")))
            run(importer(keep))
            val all = w.db.lookups().allCategories()
            assertEquals(all.filter { it.id.startsWith("srv-") }.joinToString { "${it.id}/${it.nameEn}" }, 31, all.size)
            assertEquals(
                "cat-groceries",
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0031")!!
                    .categoryId,
            )
            assertEquals(
                "cat-groceries",
                w.db
                    .budgets()
                    .byId("srv-bud-2")!!
                    .categoryId,
            )
            assertTrue(
                w.db
                    .lookups()
                    .allCategories()
                    .none { it.nameEn == "Groceries" },
            )
        }

    @Test
    fun `REQ-OPS-175 a second run refreshes an untouched row but never overwrites one changed on the phone`() =
        runTest {
            val keep = RecordingForgetter()
            run(importer(keep))
            val stamp =
                w.db
                    .sources()
                    .forExpense("srv-exp-0003")
                    .single()
                    .receivedAtMillis
            // changed on the phone: the amount edited after the import stamp (exactly one millisecond later is "changed")
            val edited = w.db.expenses().find("srv-exp-0001")!!
            w.db.expenses().update(edited.copy(amountMinor = 777, updatedAtMillis = stamp + 1))
            // deleted on the phone: must not come back
            w.db.expenses().softDelete("srv-exp-0002", w.clock.millis())
            // untouched rows: updatedAt == the stamp is NOT a change
            assertEquals(
                stamp,
                w.db
                    .expenses()
                    .find("srv-exp-0003")!!
                    .updatedAtMillis,
            )
            source.rows =
                source.rows.map {
                    val e = (it as ServerExpenseRow.Valid).expense
                    when (e.id) {
                        "srv-exp-0001", "srv-exp-0002", "srv-exp-0003" ->
                            ServerExpenseRow.Valid(
                                e.copy(amountMinor = 5_555, merchant = "Changed"),
                            )
                        else -> it
                    }
                }
            run(importer(keep))
            assertEquals(
                777L,
                w.db
                    .expenses()
                    .find("srv-exp-0001")!!
                    .amountMinor,
            )
            assertEquals(
                "Spinneys",
                w.db
                    .expenses()
                    .find("srv-exp-0001")!!
                    .merchant,
            )
            assertNotNull(
                w.db
                    .expenses()
                    .find("srv-exp-0002")!!
                    .deletedAtMillis,
            )
            assertEquals(
                5_555L,
                w.db
                    .expenses()
                    .find("srv-exp-0003")!!
                    .amountMinor,
            )
            assertEquals(
                "Changed",
                w.db
                    .expenses()
                    .find("srv-exp-0003")!!
                    .merchant,
            )
            assertEquals(1_240, count())
        }

    @Test
    fun `REQ-OPS-175 a network drop or a 5xx stops the run, keeps credentials, and Import ends at exactly 1,240`() =
        runTest {
            for (kind in listOf(ImportFailureKind.NETWORK, ImportFailureKind.SERVER)) {
                // fails on the 4th page request of this run (after page 3)
                source.failExpenseCall.clear()
                source.failExpenseCall[source.expenseCalls + 3] = kind
                assertEquals(ImportResult.Failed(kind), run())
                assertEquals("nothing is written before the download finished", 0, count())
                assertEquals(0, keystore.destroyed)
                assertTrue("credentials kept", store.stored is LoadedSession.Found)
                assertEquals(0, store.clears)
                assertTrue(auth.logoutTokens.isEmpty())
            }
            source.failExpenseCall.clear()
            assertEquals(ImportResult.Completed(1_240, 0), run())
            assertEquals(1_240, count())
        }

    @Test
    fun `REQ-OPS-175 an app kill after page 3 leaves the phone as it was and Import continues to exactly 1,240`() =
        runTest {
            source.beforeExpenseCall[3] = { throw CancellationException("killed") }
            try {
                run()
                fail("the kill must end the run")
            } catch (_: CancellationException) {
                // the process died
            }
            assertEquals(0, count())
            assertTrue(store.stored is LoadedSession.Found)
            source.beforeExpenseCall.clear()
            assertEquals(ImportResult.Completed(1_240, 0), run())
            assertEquals(1_240, count())
        }

    @Test
    fun `REQ-OPS-175 a kill while saving keeps what was written and the next run adds only the rest`() =
        runTest {
            val keep = RecordingForgetter()
            try {
                importer(keep).run { p -> if (p.stage == ImportStage.SAVING && p.done == 600) throw CancellationException("killed") }
                fail("the kill must end the run")
            } catch (_: CancellationException) {
                // the process died after 3 chunks
            }
            assertEquals(600, count())
            assertEquals(0, keep.forgets)
            assertEquals(ImportResult.Completed(1_240, 0), run(importer(keep)))
            assertEquals(1_240, count())
            assertEquals(
                1_240,
                w.db
                    .sources()
                    .all()
                    .size,
            )
        }

    @Test
    fun `REQ-OPS-175 invalid rows are skipped and counted and nothing invalid is stored`() =
        runTest {
            val good = (0 until 10).map { ServerExpenseRow.Valid(serverExpense("ok-$it", amount = 100L + it, category = "srv-cat-0")) }
            val bad =
                listOf(
                    // a row the generated model could not decode (non-integer amount, unknown enum)
                    ServerExpenseRow.Invalid,
                    ServerExpenseRow.Valid(serverExpense("bad-currency", currency = "ZZZ")),
                    ServerExpenseRow.Valid(serverExpense("bad-lower", currency = "egp")),
                    ServerExpenseRow.Valid(serverExpense("bad-type", type = "BOGUS")),
                    ServerExpenseRow.Valid(serverExpense("bad-status", status = "WEIRD")),
                    ServerExpenseRow.Valid(serverExpense("bad-category", category = "srv-cat-unknown")),
                    ServerExpenseRow.Valid(serverExpense("bad-zero", amount = 0)),
                    ServerExpenseRow.Valid(serverExpense("bad-negative", amount = -5)),
                )
            source.rows = good.take(5) + bad + good.drop(5)
            val result = run()
            assertEquals(ImportResult.Completed(10, bad.size), result)
            assertEquals(10, count())
            assertTrue(
                w.db
                    .expenses()
                    .all()
                    .none { it.serverId!!.startsWith("bad-") },
            )
            // the run completed with skipped rows: the server is forgotten
            assertEquals(1, keystore.destroyed)
        }

    @Test
    fun `REQ-OPS-175 a soft-deleted or ignored server row and a row listed twice are not imported twice`() =
        runTest {
            source.rows =
                listOf(
                    ServerExpenseRow.Valid(serverExpense("a")),
                    ServerExpenseRow.Valid(serverExpense("a")),
                    ServerExpenseRow.Valid(serverExpense("gone", deleted = true)),
                    ServerExpenseRow.Valid(serverExpense("ignored", status = "IGNORED")),
                    ServerExpenseRow.Valid(serverExpense("b", status = "POSSIBLE_DUPLICATE")),
                )
            assertEquals(ImportResult.Completed(2, 1), run())
            assertEquals(
                setOf("a", "b"),
                w.db
                    .expenses()
                    .all()
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                "PENDING_REVIEW",
                w.db
                    .expenses()
                    .find("b")!!
                    .status,
            )
        }

    @Test
    fun `REQ-OPS-175 Cairo midnight instants are stored exactly and stay on their Cairo day`() =
        runTest {
            val first = cairo("2026-11-01", "00:00")
            val last = cairo("2026-10-31", "23:59:59")
            source.rows =
                listOf(
                    ServerExpenseRow.Valid(serverExpense("midnight", at = first)),
                    ServerExpenseRow.Valid(serverExpense("before", at = last)),
                )
            run()
            assertEquals(
                first,
                w.db
                    .expenses()
                    .find("midnight")!!
                    .occurredAtMillis,
            )
            assertEquals(
                last,
                w.db
                    .expenses()
                    .find("before")!!
                    .occurredAtMillis,
            )
            assertEquals(
                "2026-11-01",
                CairoTime
                    .dateOf(
                        w.db
                            .expenses()
                            .find("midnight")!!
                            .occurredAtMillis,
                    ).toString(),
            )
            assertEquals(
                "2026-10-31",
                CairoTime
                    .dateOf(
                        w.db
                            .expenses()
                            .find("before")!!
                            .occurredAtMillis,
                    ).toString(),
            )
        }

    @Test
    fun `REQ-OPS-175 the phone's own month start day and ATM choice win over the server's`() =
        runTest {
            w.settings.update { it.copy(monthStartDay = 10) }
            w.repo.setAtmChoice(AtmChoice.TRACK)
            run()
            assertEquals(10, w.settings.current().monthStartDay)
            assertEquals(AtmChoice.TRACK, w.repo.atmChoice.first())
        }

    @Test
    fun `REQ-OPS-175 the ATM rule of the server becomes the phone's choice when none was made`() =
        runTest {
            source.settings =
                eg.masroof.data.serverimport
                    .ServerSettings(7, countAtmWithdrawalsAsSpending = false)
            run()
            assertEquals(7, w.settings.current().monthStartDay)
            assertEquals(AtmChoice.TRACK, w.repo.atmChoice.first())
            assertFalse(w.settings.current().includeCashWithdrawals)
        }

    @Test
    fun `REQ-OPS-175 a contract violation stops the run and nothing is stored`() =
        runTest {
            source.failExpenseCall[2] = ImportFailureKind.UNEXPECTED_DATA
            assertEquals(ImportResult.Failed(ImportFailureKind.UNEXPECTED_DATA), run())
            assertEquals(0, count())
            assertEquals(
                0,
                w.db
                    .budgets()
                    .all()
                    .size,
            )
            assertEquals(0, keystore.destroyed)
            assertTrue(store.stored is LoadedSession.Found)
        }

    @Test
    fun `REQ-OPS-174 without a session nothing is requested and the run says to sign in`() =
        runTest {
            store.stored = LoadedSession.Empty
            assertEquals(ImportResult.Failed(ImportFailureKind.SESSION), run())
            assertEquals(0, source.calls)
            assertEquals(0, count())
        }

    @Test
    fun `REQ-SEC-065 a completed import logs out once, deletes the session key and the server URL, and keeps local data`() =
        runTest {
            w.settings.update { it.copy(serverUrl = "https://masroof.example/") }
            w.db.expenses().insert(answerEntry("mine", source = "MANUAL"))
            assertEquals(ImportResult.Completed(1_240, 0), run())
            assertEquals(listOf("refresh-1"), auth.logoutTokens)
            assertTrue(store.stored is LoadedSession.Empty)
            assertEquals(1, keystore.destroyed)
            assertEquals("", w.settings.current().serverUrl)
            assertNull(manager.memberId())
            // REQ-AND-131: local data stays, including what the user typed
            assertNotNull(w.db.expenses().find("mine"))
            assertEquals(1_241, count())
        }

    @Test
    fun `REQ-SEC-065 a failed logout is ignored and the credentials are still deleted`() =
        runTest {
            auth.logoutOk = false
            assertEquals(ImportResult.Completed(1_240, 0), run())
            assertTrue(store.stored is LoadedSession.Empty)
            assertEquals(1, keystore.destroyed)
            assertEquals("", w.settings.current().serverUrl)
        }

    @Test
    fun `REQ-SEC-065 a keystore that cannot be deleted still clears the URL and session, and is retried until it is gone`() =
        runTest {
            var failures = 1
            val flaky =
                object : eg.masroof.data.serverimport.SessionKeystore {
                    var destroyed = 0

                    override fun destroy() {
                        if (failures-- > 0) error("keystore")
                        destroyed++
                    }
                }
            val forgetter = DefaultServerForgetter(manager, w.settings, flaky)
            // the rest still goes, the failure is reported and the forget stays pending
            assertEquals(ImportResult.Failed(ImportFailureKind.STORAGE), run(importer(forgetter)))
            assertEquals("", w.settings.current().serverUrl)
            assertTrue(store.stored is LoadedSession.Empty)
            assertTrue(importStore.forgetPending)
            assertEquals(0, flaky.destroyed)
            // the next Import (or Settings open) finishes the deletion and imports nothing again
            assertEquals(ImportResult.Completed(0, 0), run(importer(forgetter)))
            assertEquals(1, flaky.destroyed)
            assertFalse(importStore.forgetPending)
        }

    /** A settings store whose write of an empty server URL fails [failures] times with an I/O error (a full disk). */
    private inner class FlakyUrlSettings(
        var failures: Int,
    ) : eg.masroof.data.SettingsRepository by w.settings {
        override suspend fun update(transform: (eg.masroof.data.AppSettings) -> eg.masroof.data.AppSettings) {
            if (transform(w.settings.current()).serverUrl.isEmpty() && failures-- > 0) throw java.io.IOException("disk full")
            w.settings.update(transform)
        }
    }

    @Test
    fun `REQ-SEC-065 a failed write of the server URL is retried, then reported, and the next Import finishes forgetting`() =
        runTest {
            w.settings.update { it.copy(serverUrl = "https://masroof.example/") }
            val flaky = FlakyUrlSettings(failures = 3)
            val forgetter = DefaultServerForgetter(manager, flaky, keystore)
            // all 3 attempts of the first forget fail: an error state, never an exception out of run()
            assertEquals(ImportResult.Failed(ImportFailureKind.STORAGE), run(importer(forgetter)))
            assertTrue(importStore.forgetPending)
            assertEquals("https://masroof.example/", w.settings.current().serverUrl)
            val callsBefore = source.calls
            // the next tap imports nothing again and only finishes forgetting
            assertEquals(ImportResult.Completed(0, 0), run(importer(forgetter)))
            assertEquals(callsBefore, source.calls)
            assertEquals("", w.settings.current().serverUrl)
            assertFalse(importStore.forgetPending)
            assertFalse(importStore.pending)
        }

    @Test
    fun `REQ-SEC-065 a forget that fails once is retried inside the same run`() =
        runTest {
            w.settings.update { it.copy(serverUrl = "https://masroof.example/") }
            val forgetter = DefaultServerForgetter(manager, FlakyUrlSettings(failures = 1), keystore)
            assertEquals(ImportResult.Completed(1_240, 0), run(importer(forgetter)))
            assertEquals("", w.settings.current().serverUrl)
        }

    @Test
    fun `REQ-OPS-175 more than 100,000 rows from a hostile server stop the run and store nothing`() =
        runTest {
            source.rows = (0..100_000).map { ServerExpenseRow.Valid(serverExpense("h-$it")) }
            assertEquals(ImportResult.Failed(ImportFailureKind.UNEXPECTED_DATA), run())
            assertEquals(0, count())
        }

    @Test
    fun `REQ-OPS-175 a currency that is not money is not imported (XXX, XTS, XAU) and an account with one is skipped`() =
        runTest {
            source.accounts = serverAccounts() +
                eg.masroof.data.serverimport
                    .ServerAccount("acc-x", "X", "XXX", "CASH", null, null)
            source.rows =
                listOf(
                    ServerExpenseRow.Valid(serverExpense("xxx", currency = "XXX")),
                    ServerExpenseRow.Valid(serverExpense("xts", currency = "XTS")),
                    ServerExpenseRow.Valid(serverExpense("xau", currency = "XAU")),
                    ServerExpenseRow.Valid(serverExpense("usd", currency = "USD")),
                )
            // 3 entries and 1 account could not be imported; entries and others are counted apart
            assertEquals(ImportResult.Completed(1, 3, 1), run())
            assertTrue(w.db.expenses().find("xxx") == null)
        }

    @Test
    fun `REQ-OPS-175 a second import offers nothing twice (budgets skipped count apart from entries)`() =
        runTest {
            source.budgets =
                listOf(
                    eg.masroof.data.serverimport
                        .ServerBudget("b0", null, "MONTHLY", 0, "EGP", false, emptyList()),
                )
            assertEquals(ImportResult.Completed(1_240, 0, 1), run())
        }

    @Test
    fun `REQ-AND-131 Erase all data clears the import's pending flag and mappings`() =
        runTest {
            importStore.pending = true
            importStore.forgetPending = true
            importStore.mapCategory("srv-cat-0", "cat-groceries")
            importStore.mapMember("m", "member-me")
            eg.masroof.data.auth
                .RoomDataWiper(
                    w.db,
                    NoInsightsCache,
                    androidx.work.WorkManager.getInstance(ApplicationProvider.getApplicationContext()),
                    Dispatchers.Unconfined,
                    importStore = importStore,
                ).wipe()
            assertFalse(importStore.pending)
            assertFalse(importStore.forgetPending)
            assertNull(importStore.categoryOf("srv-cat-0"))
            assertNull(importStore.memberOf("m"))
        }

    @Test
    fun `REQ-OPS-175 a default category renamed on the phone before the first import is still matched to its server twin`() =
        runTest {
            val groceries =
                w.db
                    .lookups()
                    .allCategories()
                    .single { it.id == "cat-groceries" }
            w.db.lookups().upsertCategories(listOf(groceries.copy(nameEn = "Supermarket")))
            run()
            // 29 defaults + 2 custom: the server's "Groceries" did not become a third duplicate
            assertEquals(
                31,
                w.db
                    .lookups()
                    .allCategories()
                    .size,
            )
            assertEquals(
                "cat-groceries",
                w.db
                    .expenses()
                    .findByServerId("srv-exp-0031")!!
                    .categoryId,
            )
            assertTrue(
                w.db
                    .lookups()
                    .allCategories()
                    .none { it.nameEn == "Groceries" },
            )
        }

    @Test
    fun `REQ-SEC-065 a cancelled or failed run never forgets the server`() =
        runTest {
            val keep = RecordingForgetter()
            source.failExpenseCall[0] = ImportFailureKind.NETWORK
            run(importer(keep))
            assertEquals(0, keep.forgets)
            source.failExpenseCall.clear()
            run(importer(keep))
            assertEquals(1, keep.forgets)
        }
}
