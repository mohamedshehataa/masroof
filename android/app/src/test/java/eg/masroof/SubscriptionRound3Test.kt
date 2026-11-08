package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.IdFactory
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.DefaultSubscriptionRepository
import eg.masroof.data.bills.EntryFact
import eg.masroof.data.bills.KIND_SUBSCRIPTION
import eg.masroof.data.bills.NewSubscription
import eg.masroof.data.bills.SubscriptionMatcher
import eg.masroof.data.bills.SubscriptionMatcher.RowFacts
import eg.masroof.data.bills.SubscriptionService
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.SubscriptionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

private fun dayOf(iso: String): LocalDate = LocalDate.parse(iso)

class SubscriptionRound3RulesTest {
    private val renewal = dayOf("2026-11-10")

    private fun entry(
        id: String,
        merchant: String,
        amount: Long,
        date: String = "2026-11-10",
    ) = EntryFact(id, SubscriptionMatcher.keyOf(merchant)!!, merchant, amount, "EGP", dayOf(date))

    private fun matched(
        sub: String,
        amount: Long,
        vararg entries: EntryFact,
    ): String? {
        val key = SubscriptionMatcher.keyOf(sub)!!
        val row = RowFacts("row", key, amount, "EGP", renewal, entries.toList(), SubscriptionService.aliasKeysFor(key))
        return SubscriptionMatcher.bestMatch(listOf(row), emptySet())?.second?.id
    }

    @Test
    fun `REQ-AN-171 shop and food extensions are other merchants`() {
        assertNull(matched("Golds Gym", 60_000, entry("a", "GOLDS GYM PROTEIN SHOP", 60_000)))
        assertNull(matched("Uber", 5_000, entry("a", "Uber Eats", 5_200)))
        assertNull(matched("Vodafone", 29_900, entry("a", "Vodafone Store", 29_900)))
        assertNull(matched("Carrefour", 5_000, entry("a", "Carrefour Market", 5_000)))
        assertEquals("a", matched("Golds Gym", 60_000, entry("a", "GOLDS GYM", 60_000)))
    }

    @Test
    fun `REQ-AN-171 a price change is believed only for the one candidate of the row`() {
        assertEquals("a", matched("Talabat", 6_000, entry("a", "TALABAT", 10_000)))
        assertNull(matched("Talabat", 6_000, entry("a", "TALABAT", 10_000), entry("b", "TALABAT", 9_000, "2026-11-11")))
    }

    @Test
    fun `REQ-AN-171 the iCloud preset is paid through its Apple bill alias`() {
        assertEquals("a", matched("iCloud", 4_900, entry("a", "APPLE.COM/BILL", 4_900)))
        assertEquals("a", matched("iCloud", 4_900, entry("a", "iCloud", 4_900)))
    }

    @Test
    fun `REQ-AN-171 the WE preset matches its aliases exactly and never as a bare prefix`() {
        assertEquals("a", matched("WE", 40_000, entry("a", "WE INTERNET", 40_000)))
        assertEquals("a", matched("WE", 40_000, entry("a", "Telecom Egypt", 40_000)))
        assertEquals("a", matched("WE", 40_000, entry("a", "WE", 40_000)))
        assertNull(matched("WE", 40_000, entry("a", "WESTERN UNION", 40_000)))
    }

    @Test
    fun `REQ-AN-171 the Etisalat preset matches E AND and the literal brand but not E Finance`() {
        assertEquals("a", matched("etisalat", 30_000, entry("a", "E AND", 30_000)))
        assertEquals("a", matched("etisalat", 30_000, entry("a", "e&", 30_000)))
        assertEquals("a", matched("etisalat", 30_000, entry("a", "Etisalat Egypt", 30_000)))
        assertNull(matched("etisalat", 30_000, entry("a", "E Finance", 30_000)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionRound3RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DefaultSubscriptionRepository
    private val prefs = MemorySubscriptionPrefs()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
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

    private suspend fun typed(
        name: String,
        amount: Long,
        next: String,
    ): String {
        val id = ids.next()
        val new = NewSubscription(name, KIND_SUBSCRIPTION, amount, "EGP", Cadence.MONTHLY, dayOf(next))
        db.subscriptions().insert(
            SubscriptionEntity(
                id,
                new.kind,
                name,
                amount,
                "EGP",
                "MONTHLY",
                CairoTime.noonOf(new.nextRenewal).toEpochMilli(),
                null,
                null,
                null,
                1,
                true,
                null,
                0L,
            ),
        )
        return id
    }

    private suspend fun entry(
        id: String,
        merchant: String,
        amount: Long,
        date: String,
    ) = db.expenses().insert(
        ExpenseEntity(id, amount, "EGP", CairoTime.noonOf(dayOf(date)).toEpochMilli(), merchant, null, null, null, pending = false),
    )

    private suspend fun nextOf(id: String): LocalDate = CairoTime.dateOf(db.subscriptions().find(id)!!.nextRenewalAtMillis)

    @Test
    fun `REQ-AN-171 L1 a typed Netflix keeps its own key after matching a longer entry key`() =
        runTest {
            val id = typed("Netflix", 19_900, "2026-10-31")
            entry("e1", "NETFLIX.COM AMSTERDAM", 19_900, "2026-10-31")
            repo.settle(dayOf("2026-11-05"))
            assertEquals("netflix", db.subscriptions().find(id)!!.merchantKey)
            assertEquals(dayOf("2026-11-30"), nextOf(id))
            entry("e2", "NETFLIX.COM", 19_900, "2026-11-30")
            repo.settle(dayOf("2026-12-01"))
            assertEquals(dayOf("2026-12-31"), nextOf(id))
        }

    @Test
    fun `REQ-AN-171 L2 a typed Uber paid by a longer Uber entry still matches the plain Uber payment`() =
        runTest {
            val id = typed("Uber", 5_000, "2026-10-10")
            entry("e1", "Uber BV", 5_200, "2026-10-10")
            repo.settle(dayOf("2026-10-12"))
            val row = db.subscriptions().find(id)!!
            assertEquals("uber", row.merchantKey)
            assertEquals(5_200L, row.amountMinor)
            entry("e2", "Uber", 5_000, "2026-11-10")
            repo.settle(dayOf("2026-11-12"))
            assertEquals(dayOf("2026-12-10"), nextOf(id))
        }

    @Test
    fun `REQ-AN-171 Not this one restores the amount and the renewal and never matches that entry again`() =
        runTest {
            val id = typed("Talabat", 6_000, "2026-11-10")
            entry("food", "TALABAT", 10_000, "2026-11-10")
            repo.settle(dayOf("2026-11-12"))
            assertEquals(10_000L, db.subscriptions().find(id)!!.amountMinor)
            assertEquals(dayOf("2026-12-10"), nextOf(id))
            assertEquals(
                "food",
                prefs.noticeList.value
                    .single()
                    .entryId,
            )
            repo.rejectNotice(id)
            assertEquals(6_000L, db.subscriptions().find(id)!!.amountMinor)
            assertEquals(dayOf("2026-11-10"), nextOf(id))
            assertTrue(prefs.noticeList.value.isEmpty())
            repo.settle(dayOf("2026-11-12"))
            assertEquals(6_000L, db.subscriptions().find(id)!!.amountMinor)
            assertEquals(dayOf("2026-11-10"), nextOf(id))
        }
}
