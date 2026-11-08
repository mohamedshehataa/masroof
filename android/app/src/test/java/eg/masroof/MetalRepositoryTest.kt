package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.goals.GoalDraft
import eg.masroof.data.goals.GoalOutcome
import eg.masroof.data.goals.GoalRepository
import eg.masroof.data.wealth.AssetBucket
import eg.masroof.data.wealth.HoldingDraft
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MetalMath
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.data.wealth.MetalRepository
import eg.masroof.data.wealth.WealthRepository
import eg.masroof.ui.goals.cards
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetalRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var metal: MetalRepository
    private lateinit var goals: GoalRepository
    private lateinit var wealth: WealthRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 8)
    private var counter = 0
    private val ids =
        object : IdFactory {
            override fun next() = "id-${++counter}"
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        wealth = WealthRepository(db, clock)
        metal = MetalRepository(db, ids, clock, EraseLock(), wealth)
        goals = GoalRepository(db, ids, clock, EraseLock())
    }

    @After
    fun tearDown() = db.close()

    private fun day(
        m: Int,
        d: Int,
    ) = CairoTime.noonOf(LocalDate.of(2026, m, d)).toEpochMilli()

    private fun buy(
        grams: Long,
        egp: Long,
        at: Long,
        expenseId: String? = null,
    ) = LotDraft(MetalMath.BUY, grams * 1000, egp * 100, at, expenseId)

    private suspend fun wedding(): String {
        val out = metal.createHolding(HoldingDraft("GOLD", "Wedding set", 21), buy(30, 120_000, day(5, 9)))
        return (out as MetalOutcome.Saved).id
    }

    private suspend fun workedExample(): String {
        val id = wedding()
        assertTrue(metal.addLot(id, buy(10, 45_000, day(6, 1))) is MetalOutcome.Saved)
        assertTrue(metal.addLot(id, LotDraft(MetalMath.SELL, 5_000, 2_400_000, day(7, 1))) is MetalOutcome.Saved)
        assertTrue(metal.setPrice("GOLD", 21, 480_000) is MetalOutcome.Saved)
        return id
    }

    private fun entry(id: String) =
        ExpenseEntity(id, 12_000_000L, "EGP", day(5, 9), "Jeweller", null, null, null, false, createdAtMillis = 1L, updatedAtMillis = 1L)

    @Test
    fun `REQ-EXP-063 a gold holding stores one asset with karat 21 and one BUY lot of 30000 mg for 12000000`() =
        runTest {
            val id = wedding()
            val asset = db.assets().find(id)!!
            assertEquals("GOLD", asset.kind)
            assertEquals(21, asset.karat)
            assertEquals("Wedding set", asset.name)
            val lots = db.assetLots().forAsset(id)
            assertEquals(1, lots.size)
            assertEquals("BUY", lots[0].side)
            assertEquals(30_000L, lots[0].quantityMilli)
            assertEquals(12_000_000L, lots[0].totalMinor)
        }

    @Test
    fun `REQ-EXP-063 a bad name, purity or lot creates nothing`() =
        runTest {
            assertEquals(MetalOutcome.InvalidName, metal.createHolding(HoldingDraft("GOLD", " ", 21), buy(1, 1, day(5, 9))))
            assertEquals(MetalOutcome.InvalidKarat, metal.createHolding(HoldingDraft("GOLD", "x", 925), buy(1, 1, day(5, 9))))
            assertEquals(MetalOutcome.InvalidKarat, metal.createHolding(HoldingDraft("SILVER", "x", 21), buy(1, 1, day(5, 9))))
            assertEquals(
                MetalOutcome.InvalidQuantity,
                metal.createHolding(HoldingDraft("GOLD", "x", 21), LotDraft("BUY", 0, 100, day(5, 9))),
            )
            assertEquals(
                MetalOutcome.InvalidAmount,
                metal.createHolding(HoldingDraft("GOLD", "x", 21), LotDraft("BUY", 1000, 0, day(5, 9))),
            )
            assertEquals(MetalOutcome.InvalidDate, metal.createHolding(HoldingDraft("GOLD", "x", 21), buy(1, 1, day(12, 1))))
            assertTrue(
                db
                    .assets()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                db
                    .assetLots()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-EXP-063 lots can be edited and deleted, and a sale of more than is held says how much is held`() =
        runTest {
            val id = wedding()
            val sell = metal.addLot(id, LotDraft("SELL", 5_000, 2_400_000, day(7, 1))) as MetalOutcome.Saved
            assertEquals(MetalOutcome.OnlyHold(25_000L), metal.addLot(id, LotDraft("SELL", 31_000, 1_000, day(8, 1))))
            assertTrue(metal.updateLot(sell.id, LotDraft("SELL", 6_000, 2_800_000, day(7, 2))) is MetalOutcome.Saved)
            assertEquals(6_000L, db.assetLots().find(sell.id)!!.quantityMilli)
            assertEquals(MetalOutcome.OnlyHold(30_000L), metal.updateLot(sell.id, LotDraft("SELL", 31_000, 1_000, day(7, 2))))
            // Deleting the only buy under a sale is refused; deleting the sale then the buy works.
            val buyLot = db.assetLots().forAsset(id).first { it.side == "BUY" }
            assertEquals(MetalOutcome.OnlyHold(0L), metal.deleteLot(buyLot.id))
            assertTrue(metal.deleteLot(sell.id) is MetalOutcome.Saved)
            assertTrue(metal.deleteLot(buyLot.id) is MetalOutcome.Saved)
            assertNull(db.assetLots().find(buyLot.id))
        }

    @Test
    fun `REQ-AN-173 held 35 g, value 168000, average 4125, gain 23625 at 1636 bp and 3375 realised from stored lots and the typed price`() =
        runTest {
            val id = workedExample()
            val d = metal.observeHolding(id).first()!!
            val v = d.valuation
            assertEquals(35_000L, v.position.heldMilli)
            assertEquals(16_800_000L, v.valueMinor)
            assertEquals(412_500L, v.position.averagePerGramMinor)
            assertEquals(2_362_500L, v.gainMinor)
            assertEquals(1636, v.gainBp)
            assertEquals(337_500L, v.position.realisedMinor)
        }

    @Test
    fun `REQ-AN-173 the newest typed price wins and the older ones stay as history`() =
        runTest {
            val id = workedExample()
            assertTrue(metal.setPrice("GOLD", 21, 500_000) is MetalOutcome.Saved)
            assertEquals(
                17_500_000L,
                metal
                    .observeHolding(id)
                    .first()!!
                    .valuation.valueMinor,
            )
            assertEquals(
                2,
                db
                    .metalPrices()
                    .observeAll()
                    .first()
                    .size,
            )
            assertEquals(MetalOutcome.InvalidAmount, metal.setPrice("GOLD", 21, 0))
            assertEquals(MetalOutcome.InvalidKarat, metal.setPrice("GOLD", 925, 100))
        }

    @Test
    fun `REQ-AN-173 holdings feed net worth under Gold and silver, at cost without a price, and respect hide from totals`() =
        runTest {
            val id = wedding()
            val noPrice = wealth.observeNetWorth().first()
            assertEquals(12_000_000L, noPrice.buckets.first { it.bucket == AssetBucket.METAL }.amountMinor)
            metal.addLot(id, buy(10, 45_000, day(6, 1)))
            metal.addLot(id, LotDraft("SELL", 5_000, 2_400_000, day(7, 1)))
            metal.setPrice("GOLD", 21, 480_000)
            val net = wealth.observeNetWorth().first()
            assertEquals(16_800_000L, net.assetsMinor)
            assertEquals(16_800_000L, net.buckets.first { it.bucket == AssetBucket.METAL }.amountMinor)
            wealth.setAssetHidden(db.assets().find(id)!!, true)
            val hidden = wealth.observeNetWorth().first()
            assertEquals(0L, hidden.assetsMinor)
            assertFalse(hidden.hasData)
        }

    @Test
    fun `REQ-EXP-063 linking a lot to an entry excludes it from totals, Undo counts it again, deleting the lot does too`() =
        runTest {
            db.expenses().insert(entry("e1"))
            assertEquals(listOf("e1"), metal.entryCandidates().map { it.id })
            val id = wedding()
            val lot = metal.addLot(id, buy(5, 20_000, day(6, 2), "e1")) as MetalOutcome.Saved
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
            assertTrue(metal.entryCandidates().isEmpty())
            metal.detachEntry(lot.id)
            assertFalse(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
            assertNull(db.assetLots().find(lot.id)!!.expenseId)
            // Link again through an edit, then delete the lot.
            assertTrue(metal.updateLot(lot.id, buy(5, 20_000, day(6, 2), "e1")) is MetalOutcome.Saved)
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
            assertTrue(metal.deleteLot(lot.id) is MetalOutcome.Saved)
            assertFalse(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
        }

    @Test
    fun `REQ-EXP-063 deleting a holding gives its linked entries back to spending`() =
        runTest {
            db.expenses().insert(entry("e1"))
            val id = (metal.createHolding(HoldingDraft("GOLD", "Ring", 21), buy(5, 20_000, day(6, 2), "e1")) as MetalOutcome.Saved).id
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
            wealth.deleteAsset(db.assets().find(id)!!)
            assertFalse(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
            assertTrue(
                db
                    .assetLots()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    private suspend fun chalet(): String =
        (goals.create(GoalDraft("Sahel chalet", "🏖️", 500_000_00L)) as GoalOutcome.Saved).id

    @Test
    fun `REQ-AN-174 a linked holding adds its value to the goal and follows the price`() =
        runTest {
            val asset = workedExample()
            val goal = chalet()
            assertTrue(goals.record(goal, 10_000_00L, null) is GoalOutcome.Saved)
            assertTrue(metal.linkGoal(asset, goal) is MetalOutcome.Saved)
            assertEquals(goal, db.assets().find(asset)!!.goalId)
            val summary = goals.observeGoals().first().single()
            assertEquals(1_000_000L, summary.savedMinor)
            assertEquals(16_800_000L, summary.backedMinor)
            val card = cards(listOf(summary), today, 1).single()
            assertEquals(17_800_000L, card.progress.savedMinor)
            val detail = goals.observeGoal(goal).first()!!
            assertEquals("Wedding set", detail.backing.single().name)
            assertEquals(16_800_000L, detail.backing.single().valueMinor)
            // The price rises: progress follows.
            metal.setPrice("GOLD", 21, 500_000)
            assertEquals(18_500_000L, cards(goals.observeGoals().first(), today, 1).single().progress.savedMinor)
        }

    @Test
    fun `REQ-AN-174 one holding backs at most one goal, unlinking frees it`() =
        runTest {
            val asset = workedExample()
            val a = chalet()
            val b = (goals.create(GoalDraft("Car", null, 100_000_00L)) as GoalOutcome.Saved).id
            assertTrue(metal.linkGoal(asset, a) is MetalOutcome.Saved)
            assertEquals(MetalOutcome.AlreadyLinked, metal.linkGoal(asset, b))
            // A taken holding does not offer its goal twice: a is taken, b is free.
            assertEquals(
                setOf(a, b),
                metal
                    .observeLinkableGoals()
                    .first()
                    .map { it.id }
                    .toSet(),
            )
            metal.unlinkGoal(asset)
            assertNull(db.assets().find(asset)!!.goalId)
            assertEquals(
                0L,
                goals
                    .observeGoals()
                    .first()
                    .first { it.goal.id == a }
                    .backedMinor,
            )
            assertTrue(metal.linkGoal(asset, b) is MetalOutcome.Saved)
            assertEquals(MetalOutcome.NotFound, metal.linkGoal(asset, "nope"))
        }

    @Test
    fun `REQ-AN-174 deleting a linked holding unlinks it and the goal saved amount drops`() =
        runTest {
            val asset = workedExample()
            val goal = chalet()
            metal.linkGoal(asset, goal)
            assertEquals(
                16_800_000L,
                goals
                    .observeGoals()
                    .first()
                    .single()
                    .backedMinor,
            )
            wealth.deleteAsset(db.assets().find(asset)!!)
            assertEquals(
                0L,
                goals
                    .observeGoals()
                    .first()
                    .single()
                    .backedMinor,
            )
            assertNotNull(db.savingsGoals().find(goal))
            assertNull(db.assets().find(asset))
        }

    private fun total() = db.expenses().observeTotal(day(1, 1), day(12, 31))

    @Test
    fun `REQ-EXP-063 a linked entry leaves Insights and Home totals, Undo brings it back, balances keep it`() =
        runTest {
            db.expenses().insert(entry("e1").copy(instrumentLast4 = "1234"))
            db.expenses().insert(entry("e2").copy(amountMinor = 50_000L))
            val all = db.expenses().analyticsRows(day(1, 1), day(12, 31))
            assertEquals(2, all.size)
            assertEquals(12_050_000L, total().first())
            val id = wedding()
            val lot = metal.addLot(id, buy(5, 20_000, day(6, 2), "e1")) as MetalOutcome.Saved
            assertEquals(1, db.expenses().analyticsRows(day(1, 1), day(12, 31)).size)
            assertEquals(
                1,
                db
                    .expenses()
                    .observeAnalyticsRows(day(1, 1), day(12, 31))
                    .first()
                    .size,
            )
            assertEquals(50_000L, total().first())
            // Still listed, and still moves the account balance.
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .any { it.id == "e1" },
            )
            assertTrue(
                db
                    .accountBalances()
                    .observeFlowRows()
                    .first()
                    .any { it.amountMinor == 12_000_000L },
            )
            metal.detachEntry(lot.id)
            assertEquals(12_050_000L, total().first())
        }

    @Test
    fun `REQ-EXP-063 an entry that was already excluded is not claimed, so Undo cannot change it`() =
        runTest {
            db.expenses().insert(entry("e1").copy(excludedFromTotals = true))
            val id = wedding()
            val lot = metal.addLot(id, buy(5, 20_000, day(6, 2), "e1")) as MetalOutcome.Saved
            assertNull(metal.entryOf(lot.id))
            assertTrue(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
        }

    @Test
    fun `REQ-EXP-063 deleting a holding with two lots on one entry releases it`() =
        runTest {
            db.expenses().insert(entry("e1"))
            val id = (metal.createHolding(HoldingDraft("GOLD", "Ring", 21), buy(5, 20_000, day(6, 2), "e1")) as MetalOutcome.Saved).id
            val second = metal.addLot(id, buy(1, 1_000, day(6, 3))) as MetalOutcome.Saved
            db.assetLots().update(db.assetLots().find(second.id)!!.copy(expenseId = "e1"))
            metal.deleteHolding(id)
            assertFalse(
                db
                    .expenses()
                    .observeAll()
                    .first()
                    .first { it.id == "e1" }
                    .excludedFromTotals,
            )
        }

    @Test
    fun `REQ-AN-174 a goal can be backed by several holdings and only eligible goals can be linked`() =
        runTest {
            val a = workedExample()
            val b = (metal.createHolding(HoldingDraft("SILVER", "Bars", 999), buy(100, 5_000, day(6, 2))) as MetalOutcome.Saved).id
            val goal = chalet()
            assertTrue(metal.linkGoal(a, goal) is MetalOutcome.Saved)
            assertTrue(metal.linkGoal(b, goal) is MetalOutcome.Saved)
            assertEquals(
                2,
                goals
                    .observeGoal(goal)
                    .first()!!
                    .backing.size,
            )
            assertEquals(
                16_800_000L + 500_000L,
                goals
                    .observeGoals()
                    .first()
                    .single()
                    .backedMinor,
            )
            val usd = (goals.create(GoalDraft("Trip", null, 1_000_00L, "USD")) as GoalOutcome.Saved).id
            assertEquals(MetalOutcome.NotEligible, metal.linkGoal(b, usd))
            assertEquals(
                setOf(goal),
                metal
                    .observeLinkableGoals()
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }
}
