package eg.masroof

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.core.CairoTime
import eg.masroof.data.IdFactory
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.DefaultSubscriptionRepository
import eg.masroof.data.bills.EntryFact
import eg.masroof.data.bills.KIND_INCOME
import eg.masroof.data.bills.KIND_SUBSCRIPTION
import eg.masroof.data.bills.NewSubscription
import eg.masroof.data.bills.PriceNotice
import eg.masroof.data.bills.RenewalMath
import eg.masroof.data.bills.SubscriptionMatcher
import eg.masroof.data.bills.SubscriptionReminderSource
import eg.masroof.data.bills.SubscriptionService
import eg.masroof.data.bills.SubscriptionsData
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.SubscriptionEntity
import eg.masroof.ui.bills.BillsTab
import eg.masroof.ui.bills.SubscriptionsActions
import eg.masroof.ui.bills.SubscriptionsContent
import eg.masroof.ui.bills.subscriptionsState
import eg.masroof.ui.theme.MasroofTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

private fun day(iso: String): LocalDate = LocalDate.parse(iso)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionFixRoundTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DefaultSubscriptionRepository
    private lateinit var context: Context
    private val prefs = MemorySubscriptionPrefs()
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private var counter = 0
    private val ids =
        object : IdFactory {
            override fun next() = "id-${++counter}"
        }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = DefaultSubscriptionRepository(db, clock, ids, prefs, FixedSettings())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun netflix(
        next: String,
        amount: Long = 19_900,
        key: String? = "netflix",
    ) = NewSubscription("Netflix", KIND_SUBSCRIPTION, amount, "EGP", Cadence.MONTHLY, day(next), merchantKey = key)

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

    // --- 1. reminders without opening Bills ----------------------------------------------------------------

    @Test
    fun `REQ-AN-171 reminders keep coming every month without the Bills screen ever opening`() =
        runTest {
            repo.create(netflix("2026-11-09"))
            val source = SubscriptionReminderSource(db, repo)
            val dec = source.dueOn(day("2026-12-08"))
            assertEquals(1, dec.size)
            assertEquals(day("2026-12-09"), dec[0].dueDate)
            val jan = source.dueOn(day("2027-01-08"))
            assertEquals(1, jan.size)
            assertEquals(day("2027-01-09"), jan[0].dueDate)
        }

    @Test
    fun `REQ-AN-171 the reminder pass also matches a payment so the paid renewal is not reminded again`() =
        runTest {
            repo.create(netflix("2026-12-09"))
            entry("e", "NETFLIX.COM", 19_900, "2026-12-08")
            assertTrue(SubscriptionReminderSource(db, repo).dueOn(day("2026-12-08")).isEmpty())
        }

    // --- 2. anchor day ------------------------------------------------------------------------------------

    @Test
    fun `REQ-AN-171 a 30th keeps its day after February and a 28th stays on the 28th`() {
        val thirty = generateSequence(day("2027-01-30")) { RenewalMath.nextAfter(it, Cadence.MONTHLY, 30) }.take(3).toList()
        assertEquals(listOf("2027-01-30", "2027-02-28", "2027-03-30"), thirty.map { it.toString() })
        val twentyEight = generateSequence(day("2027-01-28")) { RenewalMath.nextAfter(it, Cadence.MONTHLY, 28) }.take(4).toList()
        assertEquals(listOf("2027-01-28", "2027-02-28", "2027-03-28", "2027-04-28"), twentyEight.map { it.toString() })
    }

    @Test
    fun `REQ-AN-171 the anchor day typed in the form is stored and used when rolling forward`() =
        runTest {
            val id = repo.create(netflix("2026-11-30"))
            repo.settle(day("2026-12-05"))
            assertEquals(day("2026-12-30"), nextOf(id))
            val jan = repo.create(netflix("2027-01-30", key = "other"))
            repo.settle(day("2027-03-05"))
            assertEquals(day("2027-03-30"), nextOf(jan))
        }

    @Test
    fun `REQ-AN-171 editing other fields does not replace the anchor with a clamped date`() =
        runTest {
            val id = repo.create(netflix("2027-01-30"))
            repo.settle(day("2027-02-10"))
            assertEquals(day("2027-02-28"), nextOf(id))
            repo.update(id, netflix("2027-02-28", amount = 21_000))
            repo.settle(day("2027-03-05"))
            assertEquals(day("2027-03-30"), nextOf(id))
        }

    @Test
    fun `REQ-AN-172 a salary on the 28th asked on 1 March is 27 days from payday`() =
        runTest {
            repo.create(NewSubscription("Salary", KIND_INCOME, 2_000_000, "EGP", Cadence.MONTHLY, day("2027-01-28")))
            val p = repo.daysToPayday(day("2027-03-01"))
            assertEquals(27, p.days)
            assertEquals(day("2027-03-28"), p.date)
        }

    // --- 3. price changes ---------------------------------------------------------------------------------

    @Test
    fun `REQ-AN-171 Netflix going from 199 to 229 is paid, the amount updates and the notice shows once`() =
        runTest {
            val id = raw(netflix("2026-10-31"))
            entry("e", "Netflix", 22_900, "2026-10-31")
            repo.settle(day("2026-11-08"))
            assertEquals(22_900L, db.subscriptions().find(id)!!.amountMinor)
            assertEquals(day("2026-11-30"), nextOf(id))
            assertEquals(listOf(PriceNotice(id, "Netflix", 19_900, 22_900, "EGP")), prefs.noticeList.value)
            repo.settle(day("2026-11-08"))
            assertEquals(1, prefs.noticeList.value.size)
        }

    @Test
    fun `REQ-AN-171 a price drop to half is matched, anything beyond half or double is not`() =
        runTest {
            val id = raw(netflix("2026-10-31"))
            entry("low", "Netflix", 10_000, "2026-10-31")
            repo.settle(day("2026-11-08"))
            assertEquals(10_000L, db.subscriptions().find(id)!!.amountMinor)
            val other = raw(netflix("2026-10-31", key = "spotify").copy(name = "Spotify"))
            entry("big", "Spotify", 40_000, "2026-10-31")
            repo.settle(day("2026-11-08"))
            assertEquals(19_900L, db.subscriptions().find(other)!!.amountMinor)
            assertEquals(day("2026-11-30"), nextOf(other))
        }

    // --- 4. erase -----------------------------------------------------------------------------------------

    @Test
    fun `REQ-EXP-062 Erase all data clears dismissed keys, notices and anchors`() =
        runTest {
            val id = repo.create(netflix("2026-11-20"))
            prefs.addNotice(PriceNotice(id, "Netflix", 1, 2, "EGP"))
            prefs.dismissKey("spotify")
            val wiper =
                eg.masroof.data.auth.RoomDataWiper(
                    db,
                    NoInsightsCache,
                    WorkManager.getInstance(context),
                    kotlinx.coroutines.Dispatchers.Unconfined,
                    subscriptionPrefs = prefs,
                )
            wiper.wipe()
            assertTrue(prefs.noticeList.value.isEmpty())
            assertTrue(prefs.dismissed.value.isEmpty())
            assertTrue(prefs.anchorMap.value.isEmpty())
        }

    @Test
    fun `REQ-EXP-062 a notice of a subscription that no longer exists is not shown`() =
        runTest {
            prefs.addNotice(PriceNotice("ghost", "Gone", 1, 2, "EGP"))
            val id = repo.create(netflix("2026-11-20"))
            prefs.addNotice(PriceNotice(id, "Netflix", 1, 2, "EGP"))
            val data = repo.observeData().first()
            assertEquals(listOf(id), data.notices.map { it.subscriptionId })
        }

    // --- 5. merchant keys ---------------------------------------------------------------------------------

    @Test
    fun `REQ-AN-171 the key drops domain tokens, punctuation and non-breaking spaces`() {
        assertEquals("netflix", SubscriptionMatcher.keyOf("NETFLIX.COM"))
        assertEquals("netflix", SubscriptionMatcher.keyOf("  www.Netflix.com "))
        assertEquals("watch it", SubscriptionMatcher.keyOf("Watch iT"))
        assertNull(SubscriptionMatcher.keyOf(" .com "))
        assertTrue(SubscriptionMatcher.entryKeyMatches("netflix com amsterdam".let { SubscriptionMatcher.keyOf(it)!! }, "netflix"))
        assertTrue(!SubscriptionMatcher.entryKeyMatches("netflix", "net"))
    }

    @Test
    fun `REQ-AN-171 the Netflix preset matches the NETFLIX COM merchant of the CIB sms fixture`() =
        runTest {
            val preset =
                NewSubscription(
                    "Netflix",
                    KIND_SUBSCRIPTION,
                    2_000,
                    "EGP",
                    Cadence.MONTHLY,
                    day("2026-11-10"),
                    merchantKey = SubscriptionService.NETFLIX.merchant,
                )
            val id = repo.create(preset)
            entry("fixture", "NETFLIX.COM", 2_000, "2026-11-12")
            repo.settle(day("2026-11-13"))
            assertEquals(day("2026-12-10"), nextOf(id))
        }

    @Test
    fun `REQ-AN-172 a merchant spelled with a domain does not suggest a subscription that already exists`() {
        fun fact(
            i: String,
            m: String,
            d: String,
        ) = EntryFact(i, SubscriptionMatcher.keyOf(m)!!, m, 19_900, "EGP", day(d))
        val entries =
            listOf(fact("1", "NETFLIX.COM", "2026-09-05"), fact("2", "Netflix", "2026-10-05"), fact("3", "netflix.com", "2026-11-04"))
        val none = SubscriptionMatcher.suggest(entries, setOf("netflix"), emptySet(), day("2026-11-08"))
        assertTrue(none.isEmpty())
        val one = SubscriptionMatcher.suggest(entries, emptySet(), emptySet(), day("2026-11-08"))
        assertEquals(1, one.size)
        assertEquals("netflix", one[0].key)
    }

    // --- minor --------------------------------------------------------------------------------------------

    @Test
    fun `REQ-AN-171 creating a row settles at once and resuming skips what was paid before the resume`() =
        runTest {
            entry("old", "Netflix", 19_900, "2026-11-01")
            val id = repo.create(netflix("2026-11-02"))
            // created with a payment already in the books: matched without opening the screen
            assertEquals(day("2026-12-02"), nextOf(id))
            val again = repo.create(netflix("2026-11-10", key = "other").copy(name = "Other"))
            repo.setActive(again, false)
            entry("before", "Other", 19_900, "2026-11-07")
            repo.setActive(again, true)
            repo.settle(day("2026-11-08"))
            // the entry from before the resume did not pay the 10 Nov renewal, not even in a later pass
            assertEquals(day("2026-11-10"), nextOf(again))
            entry("after", "Other", 19_900, "2026-11-09")
            repo.settle(day("2026-11-09"))
            assertEquals(day("2026-12-10"), nextOf(again))
        }

    @Test
    fun `REQ-AN-170 totals saturate instead of overflowing`() {
        val big = subRow("b", "Huge", Long.MAX_VALUE / 2)
        val totals = RenewalMath.totals(listOf(big, big))
        assertEquals(Long.MAX_VALUE, totals.perYearMinor)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class SubscriptionOtherCurrencyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-AN-170 a non-EGP row is listed but left out of the EGP totals with the standard hint`() {
        val rows =
            listOf(
                subRow("n", "Netflix", 19_900, next = "2026-11-11"),
                subRow("c", "ChatGPT", 2_000, next = "2026-11-12").copy(currency = "USD"),
            )
        val state =
            subscriptionsState(SubscriptionsData(rows, emptyList(), emptySet(), emptyList()), day("2026-11-08"), YearMonth.of(2026, 11), 1)
        compose.setContent {
            MasroofTheme { SubscriptionsContent(state, BillsTab.SUBSCRIPTIONS, SubscriptionsActions({}, {}, {}, {}, {}, {})) }
        }
        compose.onNodeWithText("Plans in other currencies are listed but not counted in the EGP totals.").assertIsDisplayed()
        compose.onAllNodesWithText("199 EGP", substring = true).onFirst().assertExists()
        compose.onNodeWithText("2 active").assertIsDisplayed()
    }
}
