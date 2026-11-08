package eg.masroof

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.IdFactory
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.SavingsGoalEntity
import eg.masroof.data.goals.GoalDraft
import eg.masroof.data.goals.GoalOutcome
import eg.masroof.data.goals.GoalRepository
import eg.masroof.ui.goals.GoalDetailViewModel
import eg.masroof.ui.goals.GoalFormViewModel
import eg.masroof.ui.goals.GoalsViewModel
import eg.masroof.ui.goals.MoneyError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoalRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: GoalRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private var counter = 0
    private val ids =
        object : IdFactory {
            override fun next() = "id-${++counter}"
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = GoalRepository(db, ids, clock, EraseLock())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private val car = GoalDraft("New car", "🚗", 85_000_000L, targetAtMillis = Instant.parse("2028-04-01T10:00:00Z").toEpochMilli())

    private fun expenseCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM expense").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private suspend fun newCar(): String = (repo.create(car) as GoalOutcome.Saved).id

    @Test
    fun `REQ-EXP-056 create stores a savings_goal row`() =
        runTest {
            val id = newCar()
            val row = db.savingsGoals().find(id)!!
            assertEquals("New car", row.name)
            assertEquals("🚗", row.emoji)
            assertEquals(85_000_000L, row.targetMinor)
            assertEquals("EGP", row.currency)
            assertNull(row.completedAtMillis)
            assertNull(row.archivedAtMillis)
        }

    @Test
    fun `REQ-EXP-056 create rejects a blank name and a zero negative or oversized target`() =
        runTest {
            assertEquals(GoalOutcome.InvalidName, repo.create(car.copy(name = "  ")))
            assertEquals(GoalOutcome.InvalidAmount, repo.create(car.copy(targetMinor = 0L)))
            assertEquals(GoalOutcome.InvalidAmount, repo.create(car.copy(targetMinor = -5L)))
            assertEquals(GoalOutcome.TooLarge, repo.create(car.copy(targetMinor = 1_000_000_000_001L)))
            assertTrue(repo.observeGoals().first().isEmpty())
        }

    @Test
    fun `REQ-EXP-056 add money 5000 and take out 2000 store one movement each and progress updates`() =
        runTest {
            val id = newCar()
            assertTrue(repo.record(id, 500_000L, "salary") is GoalOutcome.Saved)
            assertTrue(repo.record(id, -200_000L, null) is GoalOutcome.Saved)
            val detail = repo.observeGoal(id).first()!!
            assertEquals(300_000L, detail.savedMinor)
            assertEquals(listOf(500_000L, -200_000L), detail.movements.map { it.amountMinor })
            assertEquals("salary", detail.movements.first().note)
            assertEquals(
                300_000L,
                repo
                    .observeGoals()
                    .first()
                    .single()
                    .savedMinor,
            )
        }

    @Test
    fun `REQ-EXP-056 taking out more than is saved stores nothing and says how much there is`() =
        runTest {
            val id = newCar()
            repo.record(id, 500_000L, null)
            assertEquals(GoalOutcome.OnlyHave(500_000L), repo.record(id, -500_001L, null))
            assertEquals(
                1,
                repo
                    .observeGoal(id)
                    .first()!!
                    .movements.size,
            )
            assertTrue(repo.record(id, -500_000L, null) is GoalOutcome.Saved)
        }

    @Test
    fun `REQ-EXP-056 a zero or oversized movement is rejected and nothing is stored`() =
        runTest {
            val id = newCar()
            assertEquals(GoalOutcome.InvalidAmount, repo.record(id, 0L, null))
            assertEquals(GoalOutcome.TooLarge, repo.record(id, 1_000_000_000_001L, null))
            assertEquals(GoalOutcome.NotFound, repo.record("missing", 100L, null))
            assertTrue(
                repo
                    .observeGoal(id)
                    .first()!!
                    .movements
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-AN-167 reaching the target sets completedAtMillis once and a take out clears it`() =
        runTest {
            val id = newCar()
            val first = repo.record(id, 85_000_000L, null) as GoalOutcome.Saved
            assertTrue(first.reachedNow)
            assertEquals(clock.millis(), db.savingsGoals().find(id)!!.completedAtMillis)
            val again = repo.record(id, 100L, null) as GoalOutcome.Saved
            assertFalse(again.reachedNow)
            repo.record(id, -10_000_000L, null)
            assertNull(db.savingsGoals().find(id)!!.completedAtMillis)
        }

    @Test
    fun `REQ-EXP-056 edit changes name emoji target and date`() =
        runTest {
            val id = newCar()
            repo.record(id, 1_000_000L, null)
            val out = repo.update(id, GoalDraft("Sahel", "🏖️", 1_000_000L, targetAtMillis = null))
            assertEquals(true, (out as GoalOutcome.Saved).reachedNow)
            val row = db.savingsGoals().find(id)!!
            assertEquals("Sahel", row.name)
            assertEquals("🏖️", row.emoji)
            assertEquals(1_000_000L, row.targetMinor)
            assertNull(row.targetAtMillis)
            assertNotNull(row.completedAtMillis)
            assertEquals(GoalOutcome.NotFound, repo.update("missing", car))
        }

    @Test
    fun `REQ-EXP-056 archive hides the goal from the list and the totals but keeps its rows`() =
        runTest {
            val id = newCar()
            repo.record(id, 100L, null)
            repo.archive(id)
            assertTrue(repo.observeGoals().first().isEmpty())
            assertNotNull(db.savingsGoals().find(id)!!.archivedAtMillis)
            assertEquals(
                1,
                repo
                    .observeGoal(id)
                    .first()!!
                    .movements.size,
            )
        }

    @Test
    fun `REQ-EXP-056 delete removes the goal and its movements and never touches entries`() =
        runTest {
            val id = newCar()
            repo.record(id, 100L, null)
            val before = expenseCount()
            repo.delete(id)
            assertNull(db.savingsGoals().find(id))
            assertTrue(
                db
                    .goalMovements()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertNull(repo.observeGoal(id).first())
            assertEquals(before, expenseCount())
        }

    @Test
    fun `REQ-EXP-056 goal movements never create or change expense rows`() =
        runTest {
            val before = expenseCount()
            val id = newCar()
            repo.record(id, 500_000L, "x")
            repo.record(id, -100_000L, null)
            repo.update(id, car.copy(name = "Car 2"))
            repo.archive(id)
            assertEquals(before, expenseCount())
        }

    @Test
    fun `REQ-AN-167 the list shows total saved of total target for EGP goals and counts other currencies apart`() =
        runTest {
            val a = newCar()
            repo.record(a, 13_500_000L, null)
            val b = (repo.create(GoalDraft("Emergency", "💰", 15_000_000L)) as GoalOutcome.Saved).id
            repo.record(b, 1_500_000L, null)
            db.savingsGoals().insert(
                SavingsGoalEntity("usd", "Trip", "✈️", 100_000L, "USD", null, 9, 1L, null, null),
            )
            val vm = GoalsViewModel(repo, FakeSettings(), clock)
            val state = vm.state.first { !it.loading }
            val totals = state.totals!!
            assertEquals(15_000_000L, totals.savedMinor)
            assertEquals(100_000_000L, totals.targetMinor)
            assertEquals(1500, totals.percentBp)
            assertEquals(1, totals.otherCurrencyCount)
            assertEquals(3, state.cards.size)
            val car = state.cards.first { it.id == a }
            assertEquals(1588, car.progress.percentBp)
        }

    @Test
    fun `REQ-AN-167 the detail view model reproduces the worked example`() =
        runTest {
            val id = newCar()
            repo.record(id, 13_500_000L, null)
            // created today in the fixed clock; today is 2026-11-08 and the date 2028-04-01
            val vm = GoalDetailViewModel(SavedStateHandle(mapOf("id" to id)), repo, FakeSettings(), clock)
            val card = vm.state.first { !it.loading }.card!!
            assertEquals(510, card.progress.daysToGo)
            assertEquals(4_205_900L, card.progress.monthlyNeedMinor)
            assertEquals(17, card.progress.remainingPeriods)
        }

    @Test
    fun `REQ-EXP-056 the detail view model shows the take out limit and field errors`() =
        runTest {
            val id = newCar()
            val vm = GoalDetailViewModel(SavedStateHandle(mapOf("id" to id)), repo, FakeSettings(), clock)
            vm.openDialog(take = false)
            vm.submit("5,000", "bonus", "")
            vm.state.first { it.history.size == 1 }
            assertNull(vm.dialog.first { it == null })
            vm.openDialog(take = true)
            vm.submit("6000", "", "")
            val limit = vm.dialog.first { it?.error == MoneyError.ONLY_HAVE }!!
            assertEquals(500_000L, limit.onlyHaveMinor)
            vm.submit("", "", "")
            assertEquals(MoneyError.AMOUNT, vm.dialog.value?.error)
            vm.submit("0", "", "")
            assertEquals(MoneyError.AMOUNT, vm.dialog.value?.error)
            vm.submit("-5", "", "")
            assertEquals(MoneyError.AMOUNT, vm.dialog.value?.error)
            vm.submit("99999999999999", "", "")
            assertEquals(MoneyError.TOO_LARGE, vm.dialog.value?.error)
            vm.submit("10", "", "not a date")
            assertEquals(MoneyError.DATE, vm.dialog.value?.error)
            assertEquals(
                1,
                db
                    .goalMovements()
                    .observeForGoal(id)
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-EXP-056 the form view model validates then stores the goal`() =
        runTest {
            val vm = GoalFormViewModel(SavedStateHandle(), repo)
            val done = CompletableDeferred<Unit>()
            vm.save { fail("invalid form must not finish") }
            assertTrue(vm.state.value.nameError)
            assertEquals(MoneyError.AMOUNT, vm.state.value.targetError)
            vm.onName("School fees")
            vm.onTarget("25,000")
            vm.onDate("2027-09-01")
            vm.save { done.complete(Unit) }
            done.await()
            val saved =
                repo
                    .observeGoals()
                    .first { it.isNotEmpty() }
                    .single()
                    .goal
            assertEquals("School fees", saved.name)
            assertEquals(2_500_000L, saved.targetMinor)
            assertNotNull(saved.targetAtMillis)
            vm.onDate("2027-13-40")
            vm.save { fail("invalid date must not finish") }
            assertTrue(vm.state.value.dateError)
        }
}
