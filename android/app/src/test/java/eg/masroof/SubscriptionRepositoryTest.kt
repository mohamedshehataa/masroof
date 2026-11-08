package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.AppSettings
import eg.masroof.data.IdFactory
import eg.masroof.data.SettingsRepository
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.DefaultSubscriptionRepository
import eg.masroof.data.bills.KIND_BILL
import eg.masroof.data.bills.KIND_INCOME
import eg.masroof.data.bills.KIND_SUBSCRIPTION
import eg.masroof.data.bills.NewSubscription
import eg.masroof.data.bills.PaydaySource
import eg.masroof.data.bills.PriceNotice
import eg.masroof.data.bills.RenewalMath
import eg.masroof.data.bills.SubscriptionPrefs
import eg.masroof.data.bills.SubscriptionReminderSource
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.SubscriptionEntity
import eg.masroof.data.db.seededCategoryId
import eg.masroof.ui.bills.SubField
import eg.masroof.ui.bills.SubFormResult
import eg.masroof.ui.bills.SubscriptionForm
import eg.masroof.ui.bills.parse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import java.time.LocalDate
import java.time.ZoneOffset

internal class MemorySubscriptionPrefs : SubscriptionPrefs {
    val dismissed = MutableStateFlow<Set<String>>(emptySet())
    val noticeList = MutableStateFlow<List<PriceNotice>>(emptyList())
    override val dismissedKeys: Flow<Set<String>> = dismissed
    override val notices: Flow<List<PriceNotice>> = noticeList
    val anchorMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val anchors: Flow<Map<String, Int>> = anchorMap

    override suspend fun setAnchor(
        id: String,
        day: Int,
    ) {
        anchorMap.value += id to day
    }

    override suspend fun removeAnchor(id: String) {
        anchorMap.value -= id
    }

    override suspend fun clear() {
        dismissed.value = emptySet()
        noticeList.value = emptyList()
        anchorMap.value = emptyMap()
    }

    override suspend fun dismissKey(key: String) {
        dismissed.value += key
    }

    override suspend fun addNotice(notice: PriceNotice) {
        noticeList.value = noticeList.value.filterNot { it.subscriptionId == notice.subscriptionId } + notice
    }

    override suspend fun clearNotice(subscriptionId: String) {
        noticeList.value = noticeList.value.filterNot { it.subscriptionId == subscriptionId }
    }
}

internal class FixedSettings(
    monthStart: Int = 1,
) : SettingsRepository {
    private val flow = MutableStateFlow(AppSettings(monthStartDay = monthStart))
    override val settings: Flow<AppSettings> = flow

    override suspend fun current(): AppSettings = flow.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        flow.value = transform(flow.value)
    }
}

private fun day(iso: String): LocalDate = LocalDate.parse(iso)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DefaultSubscriptionRepository
    private val prefs = MemorySubscriptionPrefs()
    private val today = day("2026-11-08")
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private var counter = 0
    private val ids =
        object : IdFactory {
            override fun next() = "id-${++counter}"
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = DefaultSubscriptionRepository(db, clock, ids, prefs, FixedSettings())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun netflix(
        next: String = "2026-10-31",
        amount: Long = 19_900,
    ) = NewSubscription("Netflix", KIND_SUBSCRIPTION, amount, "EGP", Cadence.MONTHLY, day(next), merchantKey = "netflix")

    private suspend fun entry(
        id: String,
        merchant: String,
        amount: Long,
        date: String,
        type: String = "EXPENSE",
    ) = db.expenses().insert(
        ExpenseEntity(
            id,
            amount,
            "EGP",
            CairoTime.noonOf(day(date)).toEpochMilli(),
            merchant,
            null,
            null,
            null,
            pending = false,
            type = type,
        ),
    )

    private suspend fun raw(new: NewSubscription): String {
        val id = ids.next()
        db.subscriptions().insert(
            SubscriptionEntity(
                id,
                new.kind,
                new.name,
                new.amountMinor,
                new.currency,
                new.cadence.key,
                CairoTime.noonOf(new.nextRenewal).toEpochMilli(),
                new.categoryId,
                new.accountId,
                new.merchantKey,
                new.reminderDaysBefore,
                true,
                null,
                0L,
            ),
        )
        return id
    }

    private suspend fun nextOf(id: String): LocalDate = CairoTime.dateOf(db.subscriptions().find(id)!!.nextRenewalAtMillis)

    // --- REQ-EXP-062 ---------------------------------------------------------------------------------------

    @Test
    fun `REQ-EXP-062 create stores a subscription row with its cadence, renewal, category and reminder`() =
        runTest {
            val id = raw(netflix("2026-11-20").copy(categoryId = seededCategoryId("subscriptions"), reminderDaysBefore = 3))
            val row = db.subscriptions().find(id)!!
            assertEquals(KIND_SUBSCRIPTION, row.kind)
            assertEquals(19_900L, row.amountMinor)
            assertEquals("MONTHLY", row.cadence)
            assertEquals(day("2026-11-20"), CairoTime.dateOf(row.nextRenewalAtMillis))
            assertEquals(seededCategoryId("subscriptions"), row.categoryId)
            assertEquals(3, row.reminderDaysBefore)
            assertTrue(row.active)
        }

    @Test
    fun `REQ-EXP-062 recurring income is stored as a kind INCOME row`() =
        runTest {
            val id = raw(NewSubscription("Salary", KIND_INCOME, 2_000_000, "EGP", Cadence.MONTHLY, day("2026-11-25")))
            assertEquals(KIND_INCOME, db.subscriptions().find(id)!!.kind)
        }

    @Test
    fun `REQ-EXP-062 edit changes every field and keeps pause state`() =
        runTest {
            val id = raw(netflix("2026-11-20"))
            repo.setActive(id, false)
            val edited =
                NewSubscription("WE internet", KIND_BILL, 40_000, "USD", Cadence.QUARTERLY, day("2027-01-05"), "cat", "acc", "we", 7)
            assertTrue(repo.update(id, edited))
            val row = db.subscriptions().find(id)!!
            assertEquals("WE internet", row.name)
            assertEquals(KIND_BILL, row.kind)
            assertEquals(40_000L, row.amountMinor)
            assertEquals("USD", row.currency)
            assertEquals("QUARTERLY", row.cadence)
            assertEquals(day("2027-01-05"), CairoTime.dateOf(row.nextRenewalAtMillis))
            assertEquals("cat", row.categoryId)
            assertEquals("acc", row.accountId)
            assertEquals("we", row.merchantKey)
            assertEquals(7, row.reminderDaysBefore)
            assertFalse(row.active)
            assertFalse(repo.update("missing", edited))
        }

    @Test
    fun `REQ-EXP-062 pause takes a row out of totals and reminders, resume puts it back`() =
        runTest {
            val id = raw(netflix("2026-11-09"))
            val source = SubscriptionReminderSource(db, repo)
            assertEquals(1, source.dueOn(today).size)
            repo.setActive(id, false)
            assertTrue(source.dueOn(today).isEmpty())
            assertEquals(0L, RenewalMath.totals(repo.observeAll().first()).perYearMinor)
            repo.setActive(id, true)
            assertEquals(1, source.dueOn(today).size)
        }

    @Test
    fun `REQ-EXP-062 delete removes the row and its price notice`() =
        runTest {
            val id = raw(netflix())
            prefs.addNotice(PriceNotice(id, "Netflix", 19_900, 22_900, "EGP"))
            repo.delete(id)
            assertNull(db.subscriptions().find(id))
            assertTrue(prefs.noticeList.value.isEmpty())
        }

    @Test
    fun `REQ-EXP-062 the form flags an empty name, a bad amount and a missing date`() {
        val empty = SubscriptionForm().parse() as SubFormResult.Errors
        assertEquals(setOf(SubField.NAME, SubField.AMOUNT, SubField.DATE), empty.fields)
        for (amount in listOf("0", "-5", "abc", "10000000001")) {
            val r = SubscriptionForm(name = "Netflix", amount = amount, date = "2026-11-20").parse() as SubFormResult.Errors
            assertEquals("amount $amount", setOf(SubField.AMOUNT), r.fields)
        }
        val badDate = SubscriptionForm(name = "Netflix", amount = "199", date = "tomorrow").parse() as SubFormResult.Errors
        assertEquals(setOf(SubField.DATE), badDate.fields)
    }

    @Test
    fun `REQ-EXP-062 a valid form gives the row to store, a service sets its category and merchant`() {
        val ok =
            SubscriptionForm(
                serviceKey = "we",
                kind = KIND_BILL,
                name = "WE",
                amount = "400",
                date = "2026-11-20",
                cadence = Cadence.MONTHLY,
            ).parse() as SubFormResult.Ok
        assertEquals(40_000L, ok.value.amountMinor)
        assertEquals(seededCategoryId("bills.internet"), ok.value.categoryId)
        assertEquals("we", ok.value.merchantKey)
        val income =
            SubscriptionForm(kind = KIND_INCOME, name = "Salary", amount = "20,000", date = "2026-11-25").parse() as SubFormResult.Ok
        assertEquals(2_000_000L, income.value.amountMinor)
        assertNull(income.value.categoryId)
        val typed =
            SubscriptionForm(
                name = "Gym club",
                amount = "6000",
                date = "2026-11-20",
                merchant = " Gold Gym ",
            ).parse() as SubFormResult.Ok
        assertEquals("gold gym", typed.value.merchantKey)
        assertEquals(seededCategoryId("subscriptions"), typed.value.categoryId)
    }

    // --- REQ-AN-171 ----------------------------------------------------------------------------------------

    @Test
    fun `REQ-AN-171 an unpaid renewal rolls forward after its grace days, keeping the month end`() =
        runTest {
            val id = raw(netflix("2026-10-31"))
            repo.settle(today)
            assertEquals(day("2026-11-30"), nextOf(id))
            repo.settle(day("2026-12-05"))
            assertEquals(day("2026-12-31"), nextOf(id))
        }

    @Test
    fun `REQ-AN-171 a renewal is not rolled while a late payment can still match it`() =
        runTest {
            val id = raw(netflix("2026-11-06"))
            repo.settle(today)
            assertEquals(day("2026-11-06"), nextOf(id))
        }

    @Test
    fun `REQ-AN-171 a matching entry marks the renewal paid and advances the date`() =
        runTest {
            val id = raw(netflix("2026-10-31"))
            entry("e1", "Netflix", 19_900, "2026-11-01")
            repo.settle(today)
            assertEquals(day("2026-11-30"), nextOf(id))
            val data = repo.observeData().first()
            assertEquals(listOf("e1"), data.entries.map { it.id })
            assertTrue(prefs.noticeList.value.isEmpty())
        }

    @Test
    fun `REQ-AN-171 the merchant key is learned from the first matched entry`() =
        runTest {
            val id = raw(netflix("2026-10-31").copy(merchantKey = null, name = "Netflix"))
            entry("e1", "NETFLIX", 19_900, "2026-10-31")
            repo.settle(today)
            assertEquals("netflix", db.subscriptions().find(id)!!.merchantKey)
            assertEquals(day("2026-11-30"), nextOf(id))
        }

    @Test
    fun `REQ-AN-171 a different amount within 10 percent updates the price and shows the notice once`() =
        runTest {
            val id = raw(netflix("2026-10-31"))
            entry("e1", "Netflix", 21_000, "2026-10-30")
            repo.settle(today)
            assertEquals(21_000L, db.subscriptions().find(id)!!.amountMinor)
            assertEquals(listOf(PriceNotice(id, "Netflix", 19_900, 21_000, "EGP")), prefs.noticeList.value)
            repo.settle(today)
            assertEquals(1, prefs.noticeList.value.size)
            repo.dismissNotice(id)
            repo.settle(today)
            assertTrue(prefs.noticeList.value.isEmpty())
        }

    @Test
    fun `REQ-AN-171 an amount beyond 10 percent or a date beyond 3 days does not match`() =
        runTest {
            val id = raw(netflix("2026-11-02"))
            entry("far-amount", "Netflix", 50_000, "2026-11-02")
            entry("far-date", "Netflix", 19_900, "2026-11-07")
            repo.settle(today)
            assertEquals(day("2026-12-02"), nextOf(id))
            assertEquals(19_900L, db.subscriptions().find(id)!!.amountMinor)
        }

    @Test
    fun `REQ-AN-171 two candidate entries the closer date wins and an entry never pays two renewals`() =
        runTest {
            val first = raw(netflix("2026-11-05"))
            val second = raw(netflix("2026-11-06").copy(name = "Netflix 2"))
            entry("near", "Netflix", 19_900, "2026-11-05")
            entry("far", "Netflix", 19_900, "2026-11-08")
            repo.settle(today)
            // the first row takes the entry on its day; the second can only take the other one (2 days from 11-06)
            assertEquals(day("2026-12-05"), nextOf(first))
            assertEquals(day("2026-12-06"), nextOf(second))
        }

    @Test
    fun `REQ-AN-171 recurring income matches INCOME entries and an expense of the same name does not count`() =
        runTest {
            val id = raw(NewSubscription("Salary", KIND_INCOME, 2_000_000, "EGP", Cadence.MONTHLY, day("2026-10-25")))
            entry("out", "Salary", 2_000_000, "2026-10-26", type = "EXPENSE")
            repo.settle(day("2026-10-27"))
            assertEquals(day("2026-10-25"), nextOf(id))
            entry("in", "Salary", 2_000_000, "2026-10-26", type = "INCOME")
            repo.settle(day("2026-10-27"))
            assertEquals(day("2026-11-25"), nextOf(id))
        }

    // --- reminders -----------------------------------------------------------------------------------------

    @Test
    fun `REQ-AN-171 the reminder source reminds the day before with name and amount only`() =
        runTest {
            val id = raw(netflix("2026-11-09"))
            raw(NewSubscription("Salary", KIND_INCOME, 2_000_000, "EGP", Cadence.MONTHLY, day("2026-11-09")))
            val source = SubscriptionReminderSource(db, repo)
            assertTrue(source.dueOn(day("2026-11-07")).isEmpty())
            val due = source.dueOn(today)
            assertEquals(1, due.size)
            assertEquals("Netflix", due[0].label)
            assertEquals(19_900L, due[0].amount.amountMinor)
            assertEquals("subscriptions/$id", due[0].route)
            assertEquals(day("2026-11-09"), due[0].dueDate)
            assertEquals(1, source.dueOn(day("2026-11-09")).size)
            assertTrue(source.dueOn(day("2026-11-10")).isEmpty())
        }

    // --- REQ-AN-172 ----------------------------------------------------------------------------------------

    @Test
    fun `REQ-AN-172 days to payday use the active income row else the month start day`() =
        runTest {
            val p0 = repo.daysToPayday(today)
            assertEquals(PaydaySource.MONTH_START, p0.source)
            assertEquals(23, p0.days)
            raw(NewSubscription("Salary", KIND_INCOME, 2_000_000, "EGP", Cadence.MONTHLY, day("2026-11-25")))
            val p = repo.daysToPayday(today)
            assertEquals(17, p.days)
            assertEquals("Salary", p.name)
        }

    @Test
    fun `REQ-AN-172 the data feed carries the recent entries and a dismissed key is remembered`() =
        runTest {
            entry("a", "Netflix", 19_900, "2026-09-05")
            entry("b", "Netflix", 19_900, "2026-10-05")
            entry("c", "Netflix", 19_900, "2026-11-04")
            val data = repo.observeData().first()
            assertEquals(3, data.entries.size)
            repo.dismissSuggestion("netflix")
            assertEquals(setOf("netflix"), repo.observeData().map { it.dismissed }.first())
        }
}
