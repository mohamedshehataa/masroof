package eg.masroof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.data.edit.DefaultDuplicateRepository
import eg.masroof.data.edit.ExpenseChanges
import eg.masroof.ingest.CapturedAlertSettings
import eg.masroof.ingest.NotificationPermission
import eg.masroof.ui.viewmodel.MergeEvent
import eg.masroof.ui.viewmodel.ReviewViewModel
import eg.masroof.ui.viewmodel.SmsCaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** STORY-173: the review list's ViewModel offers the cards and merges only on a tap. State is awaited, never read right after an action. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicateReviewViewModelTest {
    private lateinit var w: AnswerWorld
    private lateinit var vm: ReviewViewModel
    private val events = MutableStateFlow<List<MergeEvent>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = AnswerWorld(ApplicationProvider.getApplicationContext<Context>())
        val repo = DefaultDuplicateRepository(w.db, w.prefs, w.clock, EraseLock(), LocalDataSignal())
        vm = ReviewViewModel(w.db, Dispatchers.Unconfined, w.clock, duplicates = repo)
    }

    @After
    fun tearDown() {
        w.close()
        Dispatchers.resetMain()
    }

    /** Room and the combined flows run on their own threads: wait (real time) until [flow] satisfies [cond]. */
    private suspend fun <T> await(
        flow: Flow<T>,
        cond: (T) -> Boolean,
    ): T = withContext(Dispatchers.Default) { withTimeout(10_000) { flow.first(cond) } }

    private suspend fun add(vararg entries: ExpenseEntity) =
        entries.forEach {
            w.db.expenses().insert(it)
            w.db.sources().insert(if (it.source == "MANUAL") manualSource(it.id) else captureSource(it.id, it.source.orEmpty()))
        }

    private fun TestScope.listen() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.merges.collect { e -> events.update { it + e } } }
    }

    @Test
    fun `REQ-DEDUP-063 the card is in the review state next to the messages and does not hide them`() =
        runTest {
            w.db.reviews().insert(
                ReviewItemEntity("r1", "SMS", "CIB", "text", "unconfirmed", ANSWER_NOW.toEpochMilli(), ANSWER_NOW.toEpochMilli()),
            )
            add(manualEntry("m"), captureEntry("c"))
            val cards = await(vm.duplicateCards) { it.isNotEmpty() }
            assertEquals(listOf("m|c"), cards.map { it.key })
            assertEquals(1, await(vm.items) { it?.size == 1 }!!.size)
        }

    @Test
    fun `REQ-DEDUP-063 the review count of Settings includes the card`() =
        runTest {
            w.db.reviews().insert(
                ReviewItemEntity("r1", "SMS", "CIB", "text", "unconfirmed", ANSWER_NOW.toEpochMilli(), ANSWER_NOW.toEpochMilli()),
            )
            add(manualEntry("m"), captureEntry("c"))
            val repo = DefaultDuplicateRepository(w.db, w.prefs, w.clock, EraseLock(), LocalDataSignal())
            val settingsVm =
                SmsCaptureViewModel(
                    MemorySettings(),
                    MemoryNotifications(),
                    object : CapturedAlertSettings {
                        override fun enabled() = false

                        override fun setEnabled(on: Boolean) = Unit
                    },
                    object : NotificationPermission {
                        override fun granted() = false
                    },
                    w.db,
                    w.clock,
                    Dispatchers.Unconfined,
                    repo,
                )
            assertEquals(2, await(settingsVm.reviewCount) { it == 2 })
        }

    @Test
    fun `REQ-DEDUP-064 a tap on Merge merges, says Merged with an Undo, and the card goes`() =
        runTest {
            listen()
            add(manualEntry("m", category = "food"), captureEntry("c"))
            val card = await(vm.duplicateCards) { it.isNotEmpty() }.single()
            vm.merge(card)
            val merged = await(events) { it.isNotEmpty() }.single()
            assertTrue(merged is MergeEvent.Merged)
            await(vm.duplicateCards) { it.isEmpty() }
            assertNotNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertEquals(
                "food",
                w.db
                    .expenses()
                    .find("c")!!
                    .categoryId,
            )
        }

    @Test
    fun `REQ-DEDUP-064 Undo from the snackbar puts the card and both entries back`() =
        runTest {
            listen()
            add(manualEntry("m", category = "food"), captureEntry("c"))
            val card = await(vm.duplicateCards) { it.isNotEmpty() }.single()
            vm.merge(card)
            val token = (await(events) { it.isNotEmpty() }.single() as MergeEvent.Merged).undo
            await(vm.duplicateCards) { it.isEmpty() }
            vm.undoMerge(token)
            assertEquals(listOf("m|c"), await(vm.duplicateCards) { it.isNotEmpty() }.map { it.key })
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertNull(
                w.db
                    .expenses()
                    .find("c")!!
                    .categoryId,
            )
        }

    @Test
    fun `REQ-DEDUP-064 Undo after an edit says it cannot undo and changes nothing`() =
        runTest {
            listen()
            add(manualEntry("m"), captureEntry("c"))
            vm.merge(await(vm.duplicateCards) { it.isNotEmpty() }.single())
            val token = (await(events) { it.isNotEmpty() }.single() as MergeEvent.Merged).undo
            w.editor.update("c", ExpenseChanges(notes = "later"))
            vm.undoMerge(token)
            assertEquals(MergeEvent.UndoFailed, await(events) { it.size == 2 }.last())
            assertNotNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
        }

    @Test
    fun `REQ-DEDUP-064 a card that went stale says it is out of date and merges nothing`() =
        runTest {
            listen()
            add(manualEntry("m"), captureEntry("c"))
            val stale = await(vm.duplicateCards) { it.isNotEmpty() }.single()
            w.editor.update("m", ExpenseChanges(amountMinor = 150_000))
            vm.merge(stale)
            assertEquals(listOf<MergeEvent>(MergeEvent.OutOfDate), await(events) { it.isNotEmpty() })
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertNull(
                w.db
                    .expenses()
                    .find("c")!!
                    .notes,
            )
        }

    @Test
    fun `REQ-DEDUP-064 two quick taps on Merge merge once and never say out of date`() =
        runTest {
            listen()
            add(manualEntry("m"), captureEntry("c"))
            val card = await(vm.duplicateCards) { it.isNotEmpty() }.single()
            vm.merge(card)
            vm.merge(card)
            await(vm.duplicateCards) { it.isEmpty() }
            assertEquals(1, await(events) { it.isNotEmpty() }.size)
            assertTrue(events.value.single() is MergeEvent.Merged)
        }

    @Test
    fun `REQ-DEDUP-065 Keep both and a swipe remove the card for good and merge nothing`() =
        runTest {
            listen()
            add(manualEntry("m"), captureEntry("c"))
            vm.keepBoth(await(vm.duplicateCards) { it.isNotEmpty() }.single())
            await(vm.duplicateCards) { it.isEmpty() }
            assertNull(
                w.db
                    .expenses()
                    .find("m")!!
                    .deletedAtMillis,
            )
            assertEquals(emptyList<MergeEvent>(), events.value)
            // another look-alike later is another pair: it still gets its own card
            add(captureEntry("c2", at = cairo(DUP_DAY, "16:00")))
            assertEquals(listOf("m|c2"), await(vm.duplicateCards) { it.isNotEmpty() }.map { it.key })
        }
}
