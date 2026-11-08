package eg.masroof

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.categories.DefaultCategoryRepository
import eg.masroof.data.categories.DefaultMerchantRuleRepository
import eg.masroof.data.categories.MAX_MERCHANT
import eg.masroof.data.categories.MAX_NAME
import eg.masroof.data.categories.MerchantKey
import eg.masroof.data.categories.RuleResult
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.db.ExpenseSourceEntity
import eg.masroof.data.db.ParsedOutboxEntity
import eg.masroof.data.db.ReviewItemEntity
import eg.masroof.ingest.CaptureQueueConverter
import eg.masroof.ui.viewmodel.ReviewViewModel
import eg.masroof.ui.viewmodel.RuleOffer
import eg.masroof.ui.viewmodel.RulePromptStage
import eg.masroof.ui.viewmodel.RulePromptViewModel
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

/** STORY-207 fix round 1: regressions found by the verifier and the security reviewer. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoriesRulesFixTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC)
    private val at = Instant.parse("2026-10-26T09:00:00Z").toEpochMilli()
    private val signal = LocalDataSignal()

    private class Counter : IdFactory {
        private var n = 0

        override fun next() = "fx-${n++}"
    }

    private val ids = Counter()

    private fun rules() = DefaultMerchantRuleRepository(db, clock, ids, signal, EraseLock())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        DefaultSeed.seed(db.openHelper.writableDatabase)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertEntry(
        id: String,
        merchant: String?,
        categoryId: String? = null,
        type: String = "EXPENSE",
    ) = db.expenses().insert(
        ExpenseEntity(
            id = id,
            amountMinor = 5_000,
            currency = "EGP",
            occurredAtMillis = at,
            merchant = merchant,
            categoryId = categoryId,
            accountId = null,
            notes = null,
            pending = false,
            source = "SMS",
            type = type,
            createdAtMillis = 1,
            updatedAtMillis = 1,
        ),
    )

    private fun queued(
        id: String,
        kind: String,
        merchant: String?,
    ) = ParsedOutboxEntity(
        localId = id,
        source = "SMS",
        kind = kind,
        amountMinor = 5_000L,
        currency = "EGP",
        occurredAtMillis = at,
        instrumentLast4 = "4512",
        senderId = "CIB",
        institution = "CIB",
        paymentMethod = "CARD",
        merchant = merchant,
        failed = false,
        createdAtMillis = at,
        claims = 0,
    )

    // ---- REQ-EXP-060

    @Test
    fun `REQ-EXP-060 observeCategories still returns only the server categories, exactly as before this story`() =
        runTest {
            val repo = DefaultExpenseRepository(db, clock, ids, signal)
            assertTrue("the phone's own defaults are not server ids", repo.observeCategories().first().isEmpty())
            val server = "0b6d3a52-0000-4000-8000-000000000001"
            db.lookups().upsertCategories(listOf(CategoryEntity(server, "Groceries", "بقالة", 10)))
            assertEquals(listOf(server), repo.observeCategories().first().map { it.id })
            assertEquals(db.lookups().observeCategories().first(), repo.observeCategories().first())
        }

    @Test
    fun `REQ-EXP-060 a custom category named like one under another parent still shows in the Add picker`() =
        runTest {
            val repo = DefaultExpenseRepository(db, clock, ids, signal)
            val custom = (DefaultCategoryRepository(db, ids, signal, EraseLock()).add("Fuel", "وقود", "⛽", "cat-dining"))
            val customId = (custom as eg.masroof.data.categories.CategoryResult.Ok).id
            val server = "0b6d3a52-0000-4000-8000-000000000001"
            db.lookups().upsertCategories(listOf(CategoryEntity(server, "Groceries", "بقالة", 10)))
            val picker = repo.observePickerCategories().first()
            assertTrue("custom Fuel under Dining out is offered", picker.any { it.id == customId })
            assertTrue("seeded Fuel under Car is offered", picker.any { it.id == "cat-car.fuel" })
            assertEquals("a server copy of a seeded category is a true duplicate", 1, picker.count { it.nameEn == "Groceries" })
            assertTrue(picker.first { it.nameEn == "Groceries" }.system)
        }

    @Test
    fun `REQ-EXP-060 category names are capped at 40 code points`() =
        runTest {
            val r = DefaultCategoryRepository(db, ids, signal, EraseLock()).add("x".repeat(60), "", null, null)
            val id = (r as eg.masroof.data.categories.CategoryResult.Ok).id
            assertEquals(
                MAX_NAME,
                db
                    .categoryRules()
                    .findCategory(id)!!
                    .nameEn.length,
            )
        }

    // ---- REQ-EXP-061

    @Test
    fun `REQ-EXP-061 digits stay in the merchant key and the rule merchant is capped at 80 code points`() =
        runTest {
            assertEquals("3m", MerchantKey.of("3M"))
            assertEquals("7-eleven", MerchantKey.of("7-Eleven"))
            val saved = rules().addRule("a".repeat(120), "cat-dining") as RuleResult.Saved
            assertEquals(MAX_MERCHANT, saved.merchantKey.length)
        }

    @Test
    fun `REQ-EXP-061 a queued notification capture is filed by a rule, but income and the institution fallback are not`() =
        runTest {
            rules().addRule("AMAZON", "cat-electronics")
            rules().addRule("CIB", "cat-fees")
            db.parsedOutbox().insert(queued("q-buy", "PURCHASE", "AMAZON"))
            db.parsedOutbox().insert(queued("q-income", "INCOME", "AMAZON"))
            db.parsedOutbox().insert(queued("q-transfer", "TRANSFER_OUT", "AMAZON"))
            db.parsedOutbox().insert(queued("q-bank", "PURCHASE", null)) // merchant falls back to the institution "CIB"
            CaptureQueueConverter(db, clock, ids).convertAll()
            val byKind = db.expenses().all().associateBy { it.type to it.merchant }
            assertEquals("cat-electronics", byKind.getValue("EXPENSE" to "AMAZON").categoryId)
            val income = byKind.getValue("INCOME" to "AMAZON")
            assertNull(income.categoryId)
            assertEquals("PENDING_REVIEW", income.status)
            assertNull(byKind.getValue("TRANSFER" to "AMAZON").categoryId)
            val fallback = byKind.getValue("EXPENSE" to "CIB")
            assertNull("an institution name is not a merchant", fallback.categoryId)
        }

    @Test
    fun `REQ-EXP-061 saving a review item files an expense by rule and leaves income uncategorised and as saved`() =
        runTest {
            rules().addRule("AMAZON", "cat-electronics")
            rules().addRule("AHMED ALI", "cat-gifts")

            suspend fun item(
                id: String,
                kind: String,
                merchant: String,
            ) = db.reviews().insert(
                ReviewItemEntity(
                    id = id,
                    source = "SMS",
                    sender = "CIB",
                    text = "x",
                    reason = "UNKNOWN_SHAPE",
                    receivedAtMillis = at,
                    createdAtMillis = at,
                    sugKind = kind,
                    sugAmountMinor = 5_000,
                    sugCurrency = "EGP",
                    sugOccurredAtMillis = at,
                    sugSenderId = "CIB",
                    sugInstitution = "CIB",
                    sugMerchant = merchant,
                ),
            )
            item("r-buy", "PURCHASE", "AMAZON")
            item("r-in", "INCOME", "AHMED ALI")
            val vm = ReviewViewModel(db, Dispatchers.Unconfined, clock)
            vm.saveNow("r-buy")
            vm.saveNow("r-in")
            val all = db.expenses().all()
            assertEquals("cat-electronics", all.single { it.type == "EXPENSE" }.categoryId)
            val income = all.single { it.type == "INCOME" }
            assertNull(income.categoryId)
            assertEquals("CONFIRMED", income.status)
        }

    @Test
    fun `REQ-EXP-061 apply to past changes expense entries only and Undo restores only untouched rows with their old update time`() =
        runTest {
            insertEntry("a", "Talabat", "cat-groceries")
            insertEntry("b", "Talabat", null)
            insertEntry("in", "Talabat", "cat-groceries", type = "INCOME")
            val change = rules().applyToPast("talabat", "cat-dining")
            assertEquals(2, change.count)
            assertEquals("cat-groceries", db.expenses().find("in")!!.categoryId)
            // the user edits "a" afterwards: Undo must not touch it
            db.categoryRules().setEntryCategory("a", "cat-health", 999)
            rules().undo(change)
            assertEquals("cat-health", db.expenses().find("a")!!.categoryId)
            val b = db.expenses().find("b")!!
            assertNull(b.categoryId)
            assertEquals("the previous update time is back", 1L, b.updatedAtMillis)
        }

    @Test
    fun `REQ-EXP-061 the prompt is not offered for income or when the merchant is only the institution name`() =
        runTest {
            insertEntry("real", "Talabat", "cat-dining")
            insertEntry("income", "Talabat", "cat-dining", type = "INCOME")
            insertEntry("bank", "CIB", "cat-dining")
            db.sources().insert(ExpenseSourceEntity("s1", "bank", "SMS", "l1", at, "CIB", null))
            insertEntry("manual", "Talabat", "cat-dining")
            assertTrue(rules().isOfferable("real"))
            assertTrue(rules().isOfferable("manual"))
            assertFalse(rules().isOfferable("income"))
            assertFalse(rules().isOfferable("bank"))
            assertFalse(rules().isOfferable("missing"))
            val vm = RulePromptViewModel(rules(), DefaultCategoryRepository(db, ids, signal, EraseLock()))
            vm.begin(RuleOffer("CIB", "cat-dining", "bank"))
            vm.finished.first { it }
            assertTrue(
                db
                    .merchantRules()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-EXP-061 a second Change them tap while the write runs is ignored and Undo still works`() =
        runTest {
            insertEntry("t1", "Talabat", "cat-dining")
            repeat(3) { insertEntry("p$it", "Talabat", "cat-groceries") }
            val vm = RulePromptViewModel(rules(), DefaultCategoryRepository(db, ids, signal, EraseLock()))
            vm.begin(RuleOffer("Talabat", "cat-dining", "t1"))
            vm.stage.first { it == RulePromptStage.AskRule }
            vm.yes()
            vm.stage.first { it is RulePromptStage.AskPast }
            vm.yes()
            vm.yes() // a fast double tap
            assertEquals(RulePromptStage.Changed(3), vm.stage.first { it is RulePromptStage.Changed })
            vm.undo()
            vm.finished.first { it }
            for (i in 0..2) assertEquals("cat-groceries", db.expenses().find("p$i")!!.categoryId)
        }
}
