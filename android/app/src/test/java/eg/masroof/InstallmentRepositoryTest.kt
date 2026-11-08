package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.CairoTime
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.bills.DefaultInstallmentRepository
import eg.masroof.data.bills.NewPlan
import eg.masroof.data.bills.PLAN_ACTIVE
import eg.masroof.data.bills.PLAN_COMPLETED
import eg.masroof.data.bills.PaymentChoice
import eg.masroof.data.bills.PlanField
import eg.masroof.data.bills.PlanValidator
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.ExpenseEntity
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val NOW: Instant = Instant.parse("2026-11-08T07:00:00Z")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallmentRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DefaultInstallmentRepository
    private var counter = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val ids =
            object : IdFactory {
                override fun next() = "id-${counter++}"
            }
        repo = DefaultInstallmentRepository(db, Clock.fixed(NOW, ZoneOffset.UTC), ids, LocalDataSignal(), EraseLock())
    }

    @After
    fun tearDown() = db.close()

    private fun d(s: String) = LocalDate.parse(s)

    private fun valu(
        count: Int = 18,
        first: String = "2026-03-05",
        paid: Int = 0,
    ) = NewPlan(
        title = "iPhone 17 Pro",
        provider = "VALU",
        monthlyMinor = 325_000,
        paymentCount = count,
        firstDue = d(first),
        alreadyPaid = paid,
    )

    private fun expense(
        id: String,
        amount: Long = 325_000,
        date: String = "2026-04-05",
        type: String = "EXPENSE",
        deleted: Long? = null,
    ) = ExpenseEntity(
        id = id,
        amountMinor = amount,
        currency = "EGP",
        occurredAtMillis = BillsFixtures.noon(date),
        merchant = "valU",
        categoryId = null,
        accountId = null,
        notes = null,
        pending = false,
        type = type,
        deletedAtMillis = deleted,
    )

    @Test
    fun `REQ-EXP-053 creating iPhone 17 Pro stores one plan and 18 payments of 325000 at Cairo noon`() =
        runTest {
            val id = repo.create(valu())
            val plan = db.installmentPlans().find(id)!!
            assertEquals("iPhone 17 Pro", plan.title)
            assertEquals("VALU", plan.provider)
            assertEquals(18, plan.paymentCount)
            assertEquals(1, plan.reminderDaysBefore)
            assertEquals(PLAN_ACTIVE, plan.status)
            val payments = db.installmentPayments().byPlan(id)
            assertEquals((1..18).toList(), payments.map { it.seq })
            assertTrue(payments.all { it.amountMinor == 325_000L && it.paidAtMillis == null && it.expenseId == null })
            val first = CairoTime.dateOf(payments.first().dueAtMillis)
            assertEquals(d("2026-03-05"), first)
            assertTrue(payments.all { CairoTime.dateOf(it.dueAtMillis).dayOfMonth == 5 })
            assertTrue(payments.all { Instant.ofEpochMilli(it.dueAtMillis).atZone(CairoTime.zone).hour == 12 })
            assertEquals(d("2027-08-05"), CairoTime.dateOf(payments.last().dueAtMillis))
        }

    @Test
    fun `REQ-EXP-053 a due day of 31 is stored clamped without drift`() =
        runTest {
            val id = repo.create(valu(count = 3, first = "2026-01-31"))
            val dates = db.installmentPayments().byPlan(id).map { CairoTime.dateOf(it.dueAtMillis) }
            assertEquals(listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31")), dates)
        }

    @Test
    fun `REQ-EXP-053 payments already paid are closed without creating entries`() =
        runTest {
            val id = repo.create(valu(paid = 9))
            val payments = db.installmentPayments().byPlan(id)
            assertEquals(9, payments.count { it.paidAtMillis != null })
            assertTrue(payments.all { it.expenseId == null })
            assertEquals(0, db.expenses().count())
        }

    @Test
    fun `REQ-EXP-053 invalid plans are rejected and nothing is stored`() =
        runTest {
            val bad =
                listOf(
                    valu().copy(monthlyMinor = 0),
                    valu().copy(monthlyMinor = -5),
                    valu().copy(monthlyMinor = 1_000_000_000_001L),
                    valu().copy(paymentCount = 0),
                    valu().copy(paymentCount = 121),
                    valu().copy(title = "  "),
                )
            bad.forEach { assertRejects { repo.create(it) } }
            assertEquals(
                0,
                db
                    .installmentPlans()
                    .observeAll()
                    .first()
                    .size,
            )
            assertEquals(
                0,
                db
                    .installmentPayments()
                    .observeAll()
                    .first()
                    .size,
            )
            assertTrue(PlanField.AMOUNT in PlanValidator.validate(valu().copy(monthlyMinor = 0)))
            assertTrue(PlanField.COUNT in PlanValidator.validate(valu().copy(paymentCount = 121)))
            assertTrue(PlanValidator.validate(valu().copy(monthlyMinor = 1_000_000_000_000L, paymentCount = 120)).isEmpty())
        }

    private suspend fun assertRejects(block: suspend () -> Unit) {
        try {
            block()
            fail("expected the write to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `REQ-EXP-054 recording payment 8 of 18 with a new entry marks it paid and creates one EXPENSE`() =
        runTest {
            val id = repo.create(valu(paid = 7, first = "2026-03-05"))
            val eighth = db.installmentPayments().byPlan(id)[7]
            val token = repo.recordPayment(eighth.id, PaymentChoice.CreateEntry)
            assertNotNull(token)
            val after = db.installmentPayments().find(eighth.id)!!
            assertEquals(NOW.toEpochMilli(), after.paidAtMillis)
            val entry = db.expenses().find(after.expenseId!!)!!
            assertEquals("EXPENSE", entry.type)
            assertEquals(325_000L, entry.amountMinor)
            assertEquals("valU", entry.merchant)
            assertEquals(1, db.expenses().count())
            // the next payment is the one to record now
            assertNull(db.installmentPayments().find(db.installmentPayments().byPlan(id)[8].id)!!.paidAtMillis)
        }

    @Test
    fun `REQ-EXP-054 link candidates are undeleted EXPENSE entries of the exact amount within 5 days and not yet linked`() =
        runTest {
            val id = repo.create(valu(first = "2026-04-05", count = 3))
            val pay = db.installmentPayments().byPlan(id)
            db.expenses().insert(expense("ok", date = "2026-04-05"))
            db.expenses().insert(expense("edge-early", date = "2026-03-31"))
            db.expenses().insert(expense("edge-late", date = "2026-04-10"))
            db.expenses().insert(expense("too-early", date = "2026-03-30"))
            db.expenses().insert(expense("too-late", date = "2026-04-11"))
            db.expenses().insert(expense("wrong-amount", amount = 325_001))
            db.expenses().insert(expense("income", type = "INCOME"))
            db.expenses().insert(expense("transfer", type = "TRANSFER"))
            db.expenses().insert(expense("deleted", deleted = 5L))
            assertEquals(setOf("edge-early", "ok", "edge-late"), repo.linkCandidates(pay[0].id).map { it.id }.toSet())
            repo.recordPayment(pay[0].id, PaymentChoice.LinkExisting("ok"))
            // an entry linked to payment 1 is no longer offered for any other payment
            db.expenses().insert(expense("second", date = "2026-05-05"))
            assertEquals(listOf("second"), repo.linkCandidates(pay[1].id).map { it.id })
        }

    @Test
    fun `REQ-EXP-054 linking writes the entry id with the paid time and creates no new entry`() =
        runTest {
            val id = repo.create(valu(first = "2026-04-05", count = 3))
            val pay = db.installmentPayments().byPlan(id)
            db.expenses().insert(expense("ok"))
            val token = repo.recordPayment(pay[0].id, PaymentChoice.LinkExisting("ok"))
            val after = db.installmentPayments().find(pay[0].id)!!
            assertEquals("ok", after.expenseId)
            assertNotNull(after.paidAtMillis)
            assertNull(token!!.createdExpenseId)
            assertEquals(1, db.expenses().count())
        }

    @Test
    fun `REQ-EXP-054 one entry links to at most one payment`() =
        runTest {
            val id = repo.create(valu(first = "2026-04-05", count = 3))
            val pay = db.installmentPayments().byPlan(id)
            db.expenses().insert(expense("only"))
            repo.recordPayment(pay[0].id, PaymentChoice.LinkExisting("only"))
            assertRejects { repo.recordPayment(pay[1].id, PaymentChoice.LinkExisting("only")) }
            // the failed attempt changed nothing
            assertNull(db.installmentPayments().find(pay[1].id)!!.paidAtMillis)
            assertEquals(1, db.installmentPayments().byPlan(id).count { it.expenseId == "only" })
        }

    @Test
    fun `REQ-EXP-054 an entry of another amount or a deleted entry cannot be linked`() =
        runTest {
            val id = repo.create(valu(first = "2026-04-05", count = 3))
            val pay = db.installmentPayments().byPlan(id)
            db.expenses().insert(expense("other", amount = 1_000))
            db.expenses().insert(expense("gone", deleted = 3L))
            listOf("other", "gone", "missing").forEach { target ->
                assertRejects { repo.recordPayment(pay[0].id, PaymentChoice.LinkExisting(target)) }
            }
            assertNull(db.installmentPayments().find(pay[0].id)!!.paidAtMillis)
        }

    @Test
    fun `REQ-EXP-054 a paid payment cannot be recorded twice`() =
        runTest {
            val id = repo.create(valu(count = 3))
            val first = db.installmentPayments().byPlan(id)[0]
            assertNotNull(repo.recordPayment(first.id, PaymentChoice.CreateEntry))
            assertNull(repo.recordPayment(first.id, PaymentChoice.CreateEntry))
            assertEquals(1, db.expenses().count())
        }

    @Test
    fun `REQ-EXP-054 undo restores the payment and removes the entry it created`() =
        runTest {
            val id = repo.create(valu(count = 3))
            val first = db.installmentPayments().byPlan(id)[0]
            val token = repo.recordPayment(first.id, PaymentChoice.CreateEntry)!!
            repo.undo(token)
            assertEquals(first, db.installmentPayments().find(first.id))
            assertEquals(0, db.expenses().count())
        }

    @Test
    fun `REQ-EXP-054 undo of a link keeps the entry and unlinks it`() =
        runTest {
            val id = repo.create(valu(first = "2026-04-05", count = 3))
            val first = db.installmentPayments().byPlan(id)[0]
            db.expenses().insert(expense("ok"))
            val token = repo.recordPayment(first.id, PaymentChoice.LinkExisting("ok"))!!
            repo.undo(token)
            assertEquals(first, db.installmentPayments().find(first.id))
            assertNotNull(db.expenses().find("ok"))
        }

    @Test
    fun `REQ-EXP-054 paying the last payment completes the plan and undo reopens it`() =
        runTest {
            val id = repo.create(valu(count = 2, paid = 1))
            val last = db.installmentPayments().byPlan(id)[1]
            val token = repo.recordPayment(last.id, PaymentChoice.MarkOnly)!!
            assertEquals(PLAN_COMPLETED, db.installmentPlans().find(id)!!.status)
            repo.undo(token)
            assertEquals(PLAN_ACTIVE, db.installmentPlans().find(id)!!.status)
            assertNull(db.installmentPayments().find(last.id)!!.paidAtMillis)
        }

    @Test
    fun `REQ-EXP-054 mark as complete closes the remaining payments without entries`() =
        runTest {
            val id = repo.create(valu(count = 6, paid = 2))
            assertTrue(repo.complete(id))
            val plan = db.installmentPlans().find(id)!!
            assertEquals(PLAN_COMPLETED, plan.status)
            assertNotNull(plan.completedAtMillis)
            val payments = db.installmentPayments().byPlan(id)
            assertTrue(payments.all { it.paidAtMillis != null })
            assertTrue(payments.all { it.expenseId == null })
            assertEquals(0, db.expenses().count())
            assertFalse(repo.complete("missing"))
        }

    @Test
    fun `REQ-EXP-054 deleting a plan removes it and its payments but linked entries stay`() =
        runTest {
            val id = repo.create(valu(first = "2026-04-05", count = 3))
            val first = db.installmentPayments().byPlan(id)[0]
            db.expenses().insert(expense("ok"))
            repo.recordPayment(first.id, PaymentChoice.LinkExisting("ok"))
            repo.delete(id)
            assertNull(db.installmentPlans().find(id))
            assertEquals(0, db.installmentPayments().byPlan(id).size)
            assertNotNull(db.expenses().find("ok"))
        }

    @Test
    fun `REQ-EXP-054 deleting a linked entry unlinks the payment instead of failing`() =
        runTest {
            val id = repo.create(valu(first = "2026-04-05", count = 3))
            val first = db.installmentPayments().byPlan(id)[0]
            db.expenses().insert(expense("ok"))
            repo.recordPayment(first.id, PaymentChoice.LinkExisting("ok"))
            db.expenses().delete("ok")
            val after = db.installmentPayments().find(first.id)!!
            assertNull(after.expenseId)
            assertNotNull(after.paidAtMillis)
        }

    private fun gam3eya() =
        NewPlan(
            title = "Office gam3eya",
            provider = "GAM3EYA",
            monthlyMinor = 200_000,
            paymentCount = 10,
            firstDue = d("2026-11-01"),
            collectionTurn = 4,
        )

    @Test
    fun `REQ-EXP-055 a gam3eya schedules one payment per member and stores the collection`() =
        runTest {
            val id = repo.create(gam3eya())
            val plan = db.installmentPlans().find(id)!!
            assertEquals(10, db.installmentPayments().byPlan(id).size)
            assertEquals(BillsFixtures.noon("2027-02-01"), plan.payoutAtMillis)
            assertEquals(2_000_000L, plan.payoutAmountMinor)
            val edited = repo.create(gam3eya().copy(payoutMinor = 1_900_000))
            assertEquals(1_900_000L, db.installmentPlans().find(edited)!!.payoutAmountMinor)
        }

    @Test
    fun `REQ-EXP-055 a gam3eya turn outside 1 to members is rejected`() =
        runTest {
            assertTrue(PlanField.TURN in PlanValidator.validate(gam3eya().copy(collectionTurn = 0)))
            assertTrue(PlanField.TURN in PlanValidator.validate(gam3eya().copy(collectionTurn = 11)))
            assertTrue(PlanValidator.validate(gam3eya().copy(collectionTurn = 10)).isEmpty())
            assertRejects { repo.create(gam3eya().copy(collectionTurn = 11)) }
            assertEquals(
                0,
                db
                    .installmentPlans()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-EXP-055 gam3eya payments are recorded as TRANSFER entries and the collection as an incoming TRANSFER`() =
        runTest {
            val id = repo.create(gam3eya())
            val first = db.installmentPayments().byPlan(id)[0]
            repo.recordPayment(first.id, PaymentChoice.CreateEntry)
            val paid = db.expenses().find(db.installmentPayments().find(first.id)!!.expenseId!!)!!
            assertEquals("TRANSFER", paid.type)
            assertEquals("GAM3EYA_PAYMENT", paid.paymentMethod)
            assertEquals(200_000L, paid.amountMinor)
            assertNull(paid.categoryId)
            val token = repo.recordCollection(id)!!
            val collected = db.expenses().find(token.createdExpenseId!!)!!
            assertEquals("TRANSFER", collected.type)
            assertEquals("GAM3EYA_COLLECTION", collected.paymentMethod)
            assertEquals(2_000_000L, collected.amountMinor)
            assertNotNull(db.installmentPlans().find(id)!!.payoutReceivedAtMillis)
            // collected once
            assertNull(repo.recordCollection(id))
            // undo takes the collection back
            repo.undo(token)
            assertNull(db.installmentPlans().find(id)!!.payoutReceivedAtMillis)
            assertNull(db.expenses().find(collected.id))
        }

    @Test
    fun `REQ-EXP-055 recorded gam3eya movements never count as spending or income`() =
        runTest {
            val id = repo.create(gam3eya())
            repo.recordPayment(db.installmentPayments().byPlan(id)[0].id, PaymentChoice.CreateEntry)
            repo.recordCollection(id)
            val types = db.expenses().all().map { it.type }
            assertTrue(types.all { it == "TRANSFER" })
            // the analytics query for spending only reads EXPENSE rows
            val (from, to) = CairoTime.monthRangeMillis(NOW)
            assertEquals(0L, db.expenses().observeTotal(from, to).first())
        }
}
