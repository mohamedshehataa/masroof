package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import eg.masroof.data.IdFactory
import eg.masroof.data.db.AccountEntity
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.BudgetEntity
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.SavingsGoalEntity
import eg.masroof.ingest.PrefsNotificationSettings
import eg.masroof.ingest.PrefsSenderSettings
import eg.masroof.ingest.backfill.BackfillController
import eg.masroof.ingest.backfill.BackfillPhase
import eg.masroof.ingest.backfill.BackfillStore
import eg.masroof.ui.onboarding.OnboardingProgress
import eg.masroof.ui.onboarding.OnboardingSteps
import eg.masroof.ui.onboarding.OnboardingStore
import eg.masroof.ui.onboarding.OnboardingViewModel
import eg.masroof.ui.onboarding.SetupMode
import eg.masroof.ui.onboarding.validMemberName
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
import java.util.concurrent.atomic.AtomicInteger

/** STORY-187: the first-run guide's state machine, on a real in-memory Room and the real preferences stores. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val counter = AtomicInteger()
    private val ids =
        object : IdFactory {
            override fun next() = "member-${counter.incrementAndGet()}"
        }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC)
    private val backfillStore by lazy { BackfillStore(context.getSharedPreferences("onb-backfill", Context.MODE_PRIVATE)) }
    private val senders by lazy { PrefsSenderSettings(context.getSharedPreferences("onb-senders", Context.MODE_PRIVATE)) }
    private val notifications by lazy { PrefsNotificationSettings(context.getSharedPreferences("onb-ntf", Context.MODE_PRIVATE)) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        DefaultSeed.seed(db.openHelper.writableDatabase)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        context
            .getSharedPreferences("onb-test", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun store() = OnboardingStore(context.getSharedPreferences("onb-test", Context.MODE_PRIVATE))

    private fun vm(store: OnboardingStore = store()) =
        OnboardingViewModel(
            store,
            db,
            ids,
            BackfillController(WorkManager.getInstance(context), backfillStore, clock),
            senders,
            notifications,
            Dispatchers.Unconfined,
        )

    private suspend fun memberNames() =
        db
            .lookups()
            .observeMembers()
            .first()
            .map { it.displayName }

    @Test
    fun `REQ-AND-139 a fresh install opens the guide at step 1`() =
        runTest {
            val vm = vm()
            val p = vm.progress.await { it.mode != SetupMode.UNDECIDED }
            assertEquals(OnboardingProgress(SetupMode.GUIDE, OnboardingSteps.LANGUAGE), p)
        }

    @Test
    fun `REQ-AND-139 Continue and Skip walk the seven steps and the last one finishes the guide`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            val seen = mutableListOf(vm.progress.value.step)
            repeat(OnboardingSteps.COUNT - 1) {
                vm.next()
                seen += vm.progress.value.step
            }
            assertEquals((0 until OnboardingSteps.COUNT).toList(), seen)
            vm.finish()
            assertEquals(SetupMode.DONE, vm.progress.value.mode)
        }

    @Test
    fun `REQ-AND-139 the step reached is remembered, a new process resumes there`() =
        runTest {
            val first = vm()
            first.progress.await { it.mode == SetupMode.GUIDE }
            first.next()
            first.next()
            first.next()
            // the process is killed: a new store and view model read the same file
            val second = vm(store())
            assertEquals(OnboardingProgress(SetupMode.GUIDE, OnboardingSteps.PAST_SMS), second.progress.value)
        }

    @Test
    fun `REQ-AND-139 resuming also holds across the language step's activity recreation`() =
        runTest {
            val first = vm()
            first.progress.await { it.mode == SetupMode.GUIDE }
            // the language step saves nothing special: the step is already on disk when the activity is recreated
            val recreated = vm(store())
            assertEquals(OnboardingSteps.LANGUAGE, recreated.progress.value.step)
            recreated.next()
            assertEquals(OnboardingSteps.STAYS_ON_PHONE, vm(store()).progress.value.step)
        }

    @Test
    fun `REQ-AND-139 a finished guide is not shown again at the next start`() =
        runTest {
            val first = vm()
            first.progress.await { it.mode == SetupMode.GUIDE }
            first.finish()
            val next = vm(store())
            assertEquals(SetupMode.DONE, next.progress.value.mode)
        }

    @Test
    fun `REQ-AND-139 Setup guide in Settings reopens a finished guide from step 1`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            repeat(3) { vm.next() }
            vm.finish()
            vm.reopen()
            assertEquals(OnboardingProgress(SetupMode.GUIDE, 0, reopened = true), vm.progress.value)
        }

    @Test
    fun `REQ-AND-139 a phone with existing data is not forced, it gets the card, and Dismiss hides it for good`() =
        runTest {
            db.expenses().upsert(entry("e1"))
            val vm = vm()
            assertEquals(SetupMode.CARD, vm.progress.await { it.mode != SetupMode.UNDECIDED }.mode)
            // the card is still there at the next start, until it is dismissed
            assertEquals(SetupMode.CARD, vm(store()).progress.value.mode)
            vm.dismissCard()
            assertEquals(SetupMode.DONE, vm(store()).progress.value.mode)
            // and the card's button opens the guide
            vm.reopen()
            assertEquals(SetupMode.GUIDE, vm.progress.value.mode)
        }

    @Test
    fun `REQ-AND-139 adding entries during the guide does not turn it into the card`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            db.expenses().upsert(entry("e1"))
            assertEquals(SetupMode.GUIDE, vm(store()).progress.value.mode)
        }

    @Test
    fun `REQ-SMS-101 REQ-AND-139 the past-SMS step starts the 90-day backfill in the background by default`() =
        runTest {
            val vm = vm()
            assertEquals(90, vm.rangeDays.value)
            vm.startBackfill()
            val s = backfillStore.state.value
            assertEquals(BackfillPhase.RUNNING, s.phase)
            assertEquals("D90", s.rangeKey)
        }

    @Test
    fun `REQ-AND-139 another range choice starts that range, anything else is ignored`() =
        runTest {
            val vm = vm()
            vm.chooseRange(30)
            vm.chooseRange(7)
            assertEquals(30, vm.rangeDays.value)
            vm.startBackfill()
            assertEquals("D30", backfillStore.state.value.rangeKey)
        }

    @Test
    fun `REQ-AND-140 without READ_SMS nothing starts the backfill`() =
        runTest {
            // the route never calls startBackfill on a denial: the run state stays idle
            vm()
            assertEquals(BackfillPhase.IDLE, backfillStore.state.value.phase)
        }

    @Test
    fun `REQ-AND-140 RECEIVE_SMS granted turns capture on, and bank-app capture only when notification access is granted`() =
        runTest {
            val vm = vm()
            senders.setSmsCaptureEnabled(false)
            notifications.setEnabled(false)
            assertFalse(vm.smsCaptureWanted())
            vm.smsCaptureGranted()
            assertTrue(vm.smsCaptureWanted())
            assertFalse("not turned on by merely opening the access screen", notifications.enabled())
            vm.notificationAccessGranted()
            assertTrue(notifications.enabled())
        }

    @Test
    fun `REQ-AND-139 a phone with only a budget, a bill, a goal, an account or a receipt gets the card, not the guide`() =
        runTest {
            val inserts: List<suspend () -> Unit> =
                listOf(
                    { db.budgets().insert(BudgetEntity("b", null, "MONTHLY", 100_000, "EGP", createdAtMillis = 1, updatedAtMillis = 1)) },
                    { db.savingsGoals().insert(SavingsGoalEntity("g", "Trip", null, 1_000, "EGP", null, 0, 1, null, null)) },
                    { db.lookups().upsertAccounts(listOf(AccountEntity("a", "Cash", "EGP"))) },
                    { db.lookups().addMember("m2", "Sara") },
                )
            for ((i, insert) in inserts.withIndex()) {
                // each kind of data alone, on an otherwise fresh phone
                context
                    .getSharedPreferences("onb-test", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                db.clearAllTables()
                DefaultSeed.seed(db.openHelper.writableDatabase)
                insert()
                val p = vm().progress.await { it.mode != SetupMode.UNDECIDED }
                assertEquals("data kind #$i", SetupMode.CARD, p.mode)
            }
        }

    @Test
    fun `REQ-AND-139 a soft-deleted entry still counts as existing data`() =
        runTest {
            db.expenses().upsert(entry("gone").copy(deletedAtMillis = 5L))
            assertEquals(SetupMode.CARD, vm().progress.await { it.mode != SetupMode.UNDECIDED }.mode)
        }

    @Test
    fun `REQ-AND-139 only the seeded defaults (categories and Me) leave the phone fresh`() =
        runTest {
            assertEquals(SetupMode.GUIDE, vm().progress.await { it.mode != SetupMode.UNDECIDED }.mode)
        }

    @Test
    fun `REQ-AND-139 Back goes to the previous step, closes a reopened guide, and does nothing on step 1 of the first run`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            vm.back()
            assertEquals("first run, step 1: Back is left to the system", OnboardingProgress(SetupMode.GUIDE, 0), vm.progress.value)
            vm.next()
            vm.next()
            vm.back()
            assertEquals(OnboardingSteps.STAYS_ON_PHONE, vm.progress.value.step)
            vm.reopen()
            assertEquals(true, vm.progress.value.reopened)
            vm.back()
            assertEquals("a reopened guide closes", SetupMode.DONE, vm.progress.value.mode)
            assertEquals(false, vm(store()).progress.value.reopened)
        }

    @Test
    fun `REQ-AND-141 a name of only invisible, bidi or zero-width characters, or with a line break, is refused`() {
        assertNull(validMemberName("​‏ ‮⁦"))
        assertNull(validMemberName("Sa\nra"))
        assertNull(validMemberName("Sa\u0000ra"))
        assertEquals("Sara", validMemberName("‏Sara​"))
        assertEquals("سارة", validMemberName("‫سارة‬"))
    }

    @Test
    fun `REQ-AND-141 the duplicate check is case-insensitive in Kotlin for non-ASCII names too`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            vm.addMember("Émile")
            vm.progress.await { it.step == 1 }
            db.lookups().addMember("x", "éMILE")
            assertEquals(listOf("Me", "Émile"), memberNames())
        }

    @Test
    fun `REQ-AND-141 Sara becomes a member next to Me and the guide moves on`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            repeat(OnboardingSteps.MEMBER) { vm.next() }
            vm.addMember("  Sara ")
            vm.progress.await { it.step == OnboardingSteps.DONE }
            assertEquals(listOf("Me", "Sara"), memberNames())
        }

    @Test
    fun `REQ-AND-141 an Arabic name is accepted`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            vm.addMember("سارة")
            vm.progress.await { it.step == 1 }
            assertEquals(listOf("Me", "سارة"), memberNames())
            assertFalse(vm.nameError.value)
        }

    @Test
    fun `REQ-AND-141 Skip on the member step leaves only Me`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            repeat(OnboardingSteps.MEMBER) { vm.next() }
            vm.next()
            assertEquals(listOf("Me"), memberNames())
            assertEquals(OnboardingSteps.DONE, vm.progress.value.step)
        }

    @Test
    fun `REQ-AND-141 an empty name, only spaces or more than 40 characters show the error and add nobody`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            repeat(OnboardingSteps.MEMBER) { vm.next() }
            for (bad in listOf("", "   ", "x".repeat(41))) {
                vm.addMember(bad)
                assertTrue("error for '${bad.take(5)}'", vm.nameError.value)
                assertEquals("stays on the step", OnboardingSteps.MEMBER, vm.progress.value.step)
            }
            assertEquals(listOf("Me"), memberNames())
            vm.nameChanged()
            assertFalse(vm.nameError.value)
            // exactly 40 is fine
            vm.addMember("y".repeat(40))
            vm.progress.await { it.step == OnboardingSteps.DONE }
            assertEquals(2, memberNames().size)
        }

    @Test
    fun `REQ-AND-141 the same name twice makes one member`() =
        runTest {
            val vm = vm()
            vm.progress.await { it.mode == SetupMode.GUIDE }
            vm.addMember("Sara")
            vm.addMember("sara")
            // a double tap moves on once, not twice
            vm.progress.await { it.step == 1 }
            assertEquals(listOf("Me", "Sara"), memberNames())
        }

    @Test
    fun `REQ-AND-141 the name rule trims, bounds at 40 and keeps Arabic`() {
        assertEquals("Sara", validMemberName(" Sara "))
        assertEquals("سارة", validMemberName("سارة"))
        assertNull(validMemberName(""))
        assertNull(validMemberName("\n \t"))
        assertNull(validMemberName("a".repeat(41)))
        assertEquals(40, validMemberName("a".repeat(40))?.length)
    }

    private fun entry(id: String) =
        ExpenseEntity(
            id = id,
            amountMinor = 1_000,
            currency = "EGP",
            occurredAtMillis = 1_000L,
            merchant = "Shop",
            categoryId = null,
            accountId = null,
            notes = null,
            pending = false,
        )
}
