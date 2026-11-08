package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.InstallmentPaymentEntity
import eg.masroof.data.db.InstallmentPlanEntity
import eg.masroof.data.wealth.AccountDraft
import eg.masroof.data.wealth.AccountKinds
import eg.masroof.data.wealth.AssetDraft
import eg.masroof.data.wealth.DebtDraft
import eg.masroof.data.wealth.WealthRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WealthDataTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: WealthRepository
    private val nowInstant = Instant.parse("2026-11-08T10:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        repo = WealthRepository(db, Clock.fixed(nowInstant, ZoneOffset.UTC))
    }

    @After
    fun tearDown() = db.close()

    private fun cairo(
        y: Int,
        m: Int,
        d: Int,
        h: Int = 12,
    ) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, CairoTime.zone).toInstant().toEpochMilli()

    private fun draft(
        label: String = "CIB debit",
        kind: String = AccountKinds.DEBIT,
        last4: String? = "1234",
        limit: Long? = null,
        statement: Int? = null,
        due: Int? = null,
    ) = AccountDraft(label, kind, "CIB", last4, "EGP", "💳", limit, statement, due)

    private suspend fun entry(
        id: String,
        accountId: String?,
        type: String,
        minor: Long,
        at: Long,
        instrumentLast4: String? = null,
        deleted: Long? = null,
    ) = db.expenses().insert(
        ExpenseEntity(
            id = id,
            amountMinor = minor,
            currency = "EGP",
            occurredAtMillis = at,
            merchant = null,
            categoryId = null,
            accountId = accountId,
            notes = null,
            pending = false,
            type = type,
            instrumentLast4 = instrumentLast4,
            deletedAtMillis = deleted,
        ),
    )

    @Test
    fun `REQ-AN-168 story example from Room gives 12,500 EGP and ignores deleted pre-check`() =
        runTest {
            val id = repo.addAccount(draft())
            val account =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .first { it.id == id }
            repo.setBalance(account, 1_000_000, cairo(2026, 11, 1, 10))
            val after = cairo(2026, 11, 2)
            entry("e1", id, "EXPENSE", 120_000, after)
            entry("e2", id, "REFUND", 20_000, after)
            entry("e3", id, "INCOME", 500_000, after)
            entry("e4", id, "CASH_WITHDRAWAL", 100_000, after)
            entry("e5", id, "TRANSFER", 50_000, after)
            entry("e6", id, "EXPENSE", 90_000, after, deleted = after + 1)
            entry("e7", id, "EXPENSE", 70_000, cairo(2026, 10, 30))
            val view = repo.observeAccountViews().first().first { it.account.id == id }
            assertEquals(1_250_000L, view.balanceMinor)
        }

    @Test
    fun `REQ-AN-168 a captured SMS with only the last four moves the card balance, once`() =
        runTest {
            val id = repo.addAccount(draft("Visa", AccountKinds.CREDIT_CARD, "1234"))
            val account =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .first { it.id == id }
            repo.setBalance(account, 480_000, cairo(2026, 11, 1))
            entry("s1", null, "EXPENSE", 30_000, cairo(2026, 11, 3), instrumentLast4 = "1234")
            assertEquals(
                510_000L,
                repo
                    .observeAccountViews()
                    .first()
                    .first { it.account.id == id }
                    .balanceMinor,
            )
            // a second active account with the same last four makes the entry ambiguous: counted for neither
            repo.addAccount(draft("Other", AccountKinds.DEBIT, "1234"))
            assertEquals(
                480_000L,
                repo
                    .observeAccountViews()
                    .first()
                    .first { it.account.id == id }
                    .balanceMinor,
            )
        }

    @Test
    fun `REQ-EXP-057 archived accounts are not offered to pickers but keep their history`() =
        runTest {
            val keep = repo.addAccount(draft("Keep", last4 = "1111"))
            val gone = repo.addAccount(draft("Gone", last4 = "2222"))
            repo.archiveAccount(gone, true)
            assertEquals(
                listOf(keep),
                db
                    .accountBalances()
                    .observeActiveAccounts()
                    .first()
                    .map { it.id },
            )
            assertEquals(
                2,
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-AN-169 the first counted item writes today's snapshot at once and a future balance time is clamped`() =
        runTest {
            val id = repo.addAccount(draft())
            val account =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .first { it.id == id }
            assertTrue(
                db
                    .netWorthSnapshots()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            repo.setBalance(account, 100_000, nowInstant.toEpochMilli() + 10_000_000)
            assertEquals(
                1,
                db
                    .netWorthSnapshots()
                    .observeAll()
                    .first()
                    .size,
            )
            assertEquals(
                nowInstant.toEpochMilli(),
                db
                    .accountBalances()
                    .observeAll()
                    .first()
                    .single()
                    .atMillis,
            )
        }

    @Test
    fun `REQ-AN-168 an account without a balance check has no balance and is not counted`() =
        runTest {
            val id = repo.addAccount(draft())
            val view = repo.observeAccountViews().first().first { it.account.id == id }
            assertNull(view.balanceMinor)
            val worth = repo.observeNetWorth().first()
            assertEquals(1, worth.uncountedAccounts)
            assertFalse(worth.hasData)
            assertEquals(0L, worth.netMinor)
        }

    @Test
    fun `REQ-EXP-057 add stores kind, last four and emoji and set balance stores a MANUAL row, archive keeps history`() =
        runTest {
            val id = repo.addAccount(draft())
            val account =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .first { it.id == id }
            assertEquals("1234", account.last4)
            assertEquals("💳", account.emoji)
            repo.setBalance(account, 1_250_050)
            val check =
                db
                    .accountBalances()
                    .observeAll()
                    .first()
                    .single()
            assertEquals("MANUAL", check.source)
            assertEquals(1_250_050L, check.balanceMinor)
            assertEquals(nowInstant.toEpochMilli(), check.atMillis)
            repo.updateAccount(id, draft(label = "Renamed", last4 = "9999"))
            repo.archiveAccount(id, true)
            val stored =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .single()
            assertTrue(stored.archived)
            assertEquals("Renamed", stored.label)
            assertEquals("EGP", stored.currency)
            assertEquals(
                1,
                db
                    .accountBalances()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-EXP-058 credit card shows outstanding, available, spent since statement and next due`() =
        runTest {
            // today in Cairo is 2026-11-08: latest statement day 20 is 2026-10-20 and the first due day after it is 5 Nov
            val id = repo.addAccount(draft("Visa", AccountKinds.CREDIT_CARD, "4321", 5_000_000, 20, 5))
            val account =
                db
                    .lookups()
                    .observeAccounts()
                    .first()
                    .first { it.id == id }
            repo.setBalance(account, 1_830_000, cairo(2026, 10, 1))
            entry("c1", id, "EXPENSE", 40_000, cairo(2026, 10, 25))
            entry("c2", id, "EXPENSE", 10_000, cairo(2026, 10, 10))
            val view = repo.observeAccountViews().first().first { it.account.id == id }
            val card = view.card!!
            assertEquals(1_830_000L + 40_000 + 10_000, card.outstandingMinor)
            assertEquals(5_000_000L - card.outstandingMinor, card.availableMinor)
            assertEquals(40_000L, card.spentSinceStatementMinor)
            assertEquals(java.time.LocalDate.of(2026, 12, 5), card.nextDue)
            val worth = repo.observeNetWorth().first()
            assertEquals(card.outstandingMinor, worth.debtsMinor)
            assertEquals(-card.outstandingMinor, worth.netMinor)
        }

    @Test
    fun `REQ-EXP-059 debts and assets are listed, settled debts leave totals, hidden assets stay listed`() =
        runTest {
            repo.addDebt(DebtDraft("Loan from Ahmed", false, 2_000_000, cairo(2027, 1, 1)))
            repo.addDebt(DebtDraft("Sara owes me", true, 100_000, null))
            repo.addAsset(AssetDraft("PROPERTY", "Flat", 150_000_000, null, null, null, null))
            repo.addAsset(AssetDraft("VEHICLE", "Car", 30_000_000, null, null, null, null))
            var worth = repo.observeNetWorth().first()
            assertEquals(150_000_000L + 30_000_000 + 100_000, worth.assetsMinor)
            assertEquals(2_000_000L, worth.debtsMinor)
            repo.setAssetHidden(
                db
                    .assets()
                    .observeAll()
                    .first()
                    .first { it.name == "Car" },
                true,
            )
            repo.settleDebt(
                db
                    .debts()
                    .observeAll()
                    .first()
                    .first { it.name == "Loan from Ahmed" },
            )
            worth = repo.observeNetWorth().first()
            assertEquals(150_000_000L + 100_000, worth.assetsMinor)
            assertEquals(0L, worth.debtsMinor)
            assertEquals(2, repo.observeAssetViews().first().size)
        }

    @Test
    fun `REQ-EXP-059 certificate and fund values accrue by days and unpaid installments count as debt`() =
        runTest {
            repo.addAsset(AssetDraft("CERTIFICATE", "NBE", null, 10_000_000, 1_800, cairo(2026, 1, 1), cairo(2027, 1, 1)))
            repo.addAsset(AssetDraft("FUND", "Fund", null, 5_000_000, 1_000, cairo(2026, 5, 1), null))
            val views = repo.observeAssetViews().first()
            // 2026-01-01 to 2026-11-08 is 311 days
            assertEquals(10_000_000L, views.first { it.asset.kind == "CERTIFICATE" }.valueMinor)
            // 2026-05-01 to 2026-11-08 is 191 days: 5,000,000 * 1000 * 191 / 3,650,000 = 261,643.8 -> 261,644
            assertEquals(5_000_000L + 261_644, views.first { it.asset.kind == "FUND" }.valueMinor)
            db.installmentPlans().insert(
                InstallmentPlanEntity(
                    "p",
                    "Phone",
                    "valU",
                    null,
                    null,
                    "EGP",
                    null,
                    0,
                    100_000,
                    2,
                    1,
                    3,
                    "ACTIVE",
                    null,
                    1,
                    null,
                    null,
                    null,
                    null,
                ),
            )
            db.installmentPayments().insert(InstallmentPaymentEntity("i1", "p", 1, 1, 100_000, 5, null))
            db.installmentPayments().insert(InstallmentPaymentEntity("i2", "p", 2, 2, 100_000, null, null))
            assertEquals(100_000L, repo.observeNetWorth().first().debtsMinor)
        }

    @Test
    fun `REQ-AN-169 snapshot is written once per Cairo day, updated in place, and skipped when nothing counts`() =
        runTest {
            assertFalse(repo.snapshotToday())
            assertTrue(
                db
                    .netWorthSnapshots()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            repo.addAsset(AssetDraft("OTHER", "Stuff", 100_000, null, null, null, null))
            assertTrue(repo.snapshotToday())
            repo.addAsset(AssetDraft("OTHER", "More", 50_000, null, null, null, null))
            assertTrue(repo.snapshotToday())
            val rows = db.netWorthSnapshots().observeAll().first()
            assertEquals(1, rows.size)
            assertEquals("2026-11-08", rows.single().cairoDate)
            assertEquals(150_000L, rows.single().netMinor)
            assertTrue(rows.single().breakdown.contains("OTHER=150000"))
            // a second day gets its own row
            val tomorrow = WealthRepository(db, Clock.fixed(nowInstant.plusSeconds(24 * 3600), ZoneOffset.UTC))
            assertTrue(tomorrow.snapshotToday())
            assertEquals(
                2,
                db
                    .netWorthSnapshots()
                    .observeAll()
                    .first()
                    .size,
            )
        }
}
