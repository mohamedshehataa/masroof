package eg.masroof

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eg.masroof.core.Money
import eg.masroof.data.DefaultExpenseRepository
import eg.masroof.data.IdFactory
import eg.masroof.data.LocalDataSignal
import eg.masroof.data.NewExpense
import eg.masroof.data.auth.EraseLock
import eg.masroof.data.categories.CategoryNameKey
import eg.masroof.data.categories.CategoryResult
import eg.masroof.data.categories.DefaultCategoryRepository
import eg.masroof.data.categories.DefaultMerchantRuleRepository
import eg.masroof.data.categories.EMOJI_CATALOG
import eg.masroof.data.categories.MerchantKey
import eg.masroof.data.categories.RuleResult
import eg.masroof.data.categories.searchEmoji
import eg.masroof.data.db.AppDatabase
import eg.masroof.data.db.CategoryEntity
import eg.masroof.data.db.DefaultSeed
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.data.edit.DefaultExpenseEditor
import eg.masroof.ingest.Disposition
import eg.masroof.ingest.SmsPipeline
import eg.masroof.ingest.parse.CaptureSource
import eg.masroof.ingest.parse.IncomingMessage
import eg.masroof.ui.viewmodel.EditExpenseViewModel
import eg.masroof.ui.viewmodel.RuleOffer
import eg.masroof.ui.viewmodel.RulePromptStage
import eg.masroof.ui.viewmodel.RulePromptViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoriesAndRulesTest {
    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC)
    private val signal = LocalDataSignal()

    private class Counter : IdFactory {
        private var n = 0

        override fun next() = "id-${n++}"
    }

    private val ids = Counter()

    private fun categories() = DefaultCategoryRepository(db, ids, signal, EraseLock())

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
        deleted: Boolean = false,
        type: String = "EXPENSE",
        amount: Long = 12_500,
    ) = db.expenses().insert(
        ExpenseEntity(
            id = id,
            amountMinor = amount,
            currency = "EGP",
            occurredAtMillis = 1_760_000_000_000,
            merchant = merchant,
            categoryId = categoryId,
            accountId = null,
            notes = null,
            pending = false,
            source = "MANUAL",
            createdAtMillis = 1,
            updatedAtMillis = 1,
            deletedAtMillis = if (deleted) 5L else null,
            type = type,
        ),
    )

    // ---- REQ-EXP-060

    @Test
    fun `REQ-EXP-060 every seeded category gets its default emoji once and a user choice is never overwritten`() =
        runTest {
            val all = db.lookups().observeLabelCategories().first()
            assertEquals("🛒", all.first { it.id == "cat-groceries" }.emoji)
            assertEquals("⛽", all.first { it.id == "cat-car.fuel" }.emoji)
            assertEquals("🍔", all.first { it.id == "cat-dining" }.emoji)
            assertEquals("📦", all.first { it.id == "cat-other" }.emoji)
            assertTrue("every seeded category has one", all.none { it.emoji == null })
            categories().edit("cat-groceries", "Groceries", "بقالة", "🍎")
            DefaultSeed.seed(db.openHelper.writableDatabase)
            DefaultSeed.seed(db.openHelper.writableDatabase)
            assertEquals("🍎", db.categoryRules().findCategory("cat-groceries")!!.emoji)
            db.lookups().upsertCategories(listOf(CategoryEntity("cat-groceries", "Groceries", "بقالة", 10, system = true)))
            assertEquals("🍎", db.categoryRules().findCategory("cat-groceries")!!.emoji)
        }

    @Test
    fun `REQ-EXP-060 the emoji grid has at least 80 distinct emoji and searches English and Arabic keywords`() {
        assertTrue(EMOJI_CATALOG.map { it.emoji }.toSet().size >= 80)
        assertTrue(searchEmoji("coffee").any { it.emoji == "☕" })
        assertTrue(searchEmoji("قهوة").any { it.emoji == "☕" })
        assertTrue(searchEmoji("بنزين").any { it.emoji == "⛽" })
        assertTrue(searchEmoji("zzzzqq").isEmpty())
        assertEquals(EMOJI_CATALOG.size, searchEmoji("  ").size)
    }

    @Test
    fun `REQ-EXP-060 adding Coffee under Dining out shows at once, with its emoji and the parent's colour`() =
        runTest {
            val r = categories().add("Coffee", "قهوة", "☕", "cat-dining") as CategoryResult.Ok
            val added =
                db
                    .lookups()
                    .observeAllCategories()
                    .first()
                    .first { it.id == r.id }
            assertEquals("Coffee", added.nameEn)
            assertEquals("قهوة", added.nameAr)
            assertEquals("☕", added.emoji)
            assertEquals("cat-dining", added.parentId)
            assertEquals("#F97316", added.color)
            assertTrue(!added.system)
            assertTrue(
                db
                    .lookups()
                    .observeCategories()
                    .first()
                    .any { it.id == r.id },
            )
        }

    @Test
    fun `REQ-EXP-060 renaming in English and Arabic and changing the emoji persist`() =
        runTest {
            assertTrue(categories().edit("cat-pets", "Pets and vets", "حيوانات وبيطري", "🐶") is CategoryResult.Ok)
            val c = db.categoryRules().findCategory("cat-pets")!!
            assertEquals("Pets and vets", c.nameEn)
            assertEquals("حيوانات وبيطري", c.nameAr)
            assertEquals("🐶", c.emoji)
            assertTrue(c.system)
        }

    @Test
    fun `REQ-EXP-060 empty names and duplicates under the same parent are refused, ignoring case, accents and Arabic spelling`() =
        runTest {
            val repo = categories()
            assertEquals(CategoryResult.EmptyName, repo.add("  ", "", "☕", null))
            assertEquals(CategoryResult.Duplicate, repo.add("GROCERIES", "x", null, null))
            assertEquals(CategoryResult.Duplicate, repo.add("Something", "بقاله", null, null)) // taa marbuta spelled as haa
            assertEquals(CategoryResult.Duplicate, repo.add("Something", "بَقَالة", null, null)) // with diacritics
            assertTrue(repo.add("Cafe", "", null, null) is CategoryResult.Ok)
            assertEquals(CategoryResult.Duplicate, repo.add("Café", "", null, null))
            // the same name under another parent is fine
            assertTrue(repo.add("Fuel", "وقود", null, "cat-dining") is CategoryResult.Ok)
            assertEquals(CategoryResult.Duplicate, repo.edit("cat-kids", "Health", "صحة", null))
            assertTrue("renaming to its own name is not a duplicate", repo.edit("cat-kids", "KIDS", "أطفال", "🧸") is CategoryResult.Ok)
            assertEquals(CategoryNameKey.of("أُخرى"), CategoryNameKey.of("اخري"))
        }

    @Test
    fun `REQ-EXP-060 a category in use cannot be deleted, an unused custom one can, a built-in one never`() =
        runTest {
            val repo = categories()
            val used = (repo.add("Hobby", "هواية", null, null) as CategoryResult.Ok).id
            val unused = (repo.add("Temp", "مؤقت", null, null) as CategoryResult.Ok).id
            val withRule = (repo.add("Ruled", "بقاعدة", null, null) as CategoryResult.Ok).id
            val parent = (repo.add("Parent", "اب", null, null) as CategoryResult.Ok).id
            repo.add("Child", "ابن", null, parent)
            insertEntry("e1", "Shop", used)
            rules().addRule("Ruled Shop", withRule)
            assertEquals(CategoryResult.InUse, repo.delete(used))
            assertEquals(CategoryResult.InUse, repo.delete(withRule))
            assertEquals(CategoryResult.InUse, repo.delete(parent))
            assertEquals(CategoryResult.SystemProtected, repo.delete("cat-groceries"))
            assertTrue(repo.delete(unused) is CategoryResult.Ok)
            assertNull(db.categoryRules().findCategory(unused))
            assertNotNull(db.categoryRules().findCategory(used))
            assertEquals(CategoryResult.NotFound, repo.delete("nope"))
        }

    @Test
    fun `REQ-EXP-060 archiving hides a category from pickers but history and rules keep working`() =
        runTest {
            val repo = categories()
            insertEntry("e1", "Talabat", "cat-dining")
            rules().addRule("Noon", "cat-dining")
            assertTrue(repo.setArchived("cat-dining", true) is CategoryResult.Ok)
            assertTrue(
                db
                    .lookups()
                    .observeAllCategories()
                    .first()
                    .none { it.id == "cat-dining" },
            )
            assertTrue(
                db
                    .lookups()
                    .observeLabelCategories()
                    .first()
                    .any { it.id == "cat-dining" },
            )
            assertTrue(
                db
                    .lookups()
                    .observeEveryCategory()
                    .first()
                    .any { it.id == "cat-dining" && it.archived },
            )
            assertEquals("cat-dining", db.expenses().find("e1")!!.categoryId)
            // a rule that points at an archived category still files new entries
            val repository = DefaultExpenseRepository(db, clock, Counter(), signal)
            val filed = repository.add(NewExpense(Money(5_000), LocalDate.of(2026, 10, 15), null, null, null, merchant = "NOON"))
            assertEquals("cat-dining", db.expenses().find(filed)!!.categoryId)
            repo.setArchived("cat-dining", false)
            assertTrue(
                db
                    .lookups()
                    .observeAllCategories()
                    .first()
                    .any { it.id == "cat-dining" },
            )
        }

    @Test
    fun `REQ-EXP-060 reordering moves a category one place among its siblings`() =
        runTest {
            val repo = categories()
            repo.move("cat-other", true)
            val tops =
                db
                    .categoryRules()
                    .allCategories()
                    .filter { it.parentId == null }
                    .sortedBy { it.sortOrder }
                    .map { it.id }
            assertEquals("cat-other", tops[tops.size - 2])
            assertEquals("cat-pets", tops.last())
            repo.move("cat-groceries", true) // first stays first
            assertEquals(
                "cat-groceries",
                db
                    .categoryRules()
                    .allCategories()
                    .filter { it.parentId == null }
                    .minBy { it.sortOrder }
                    .id,
            )
            repo.move("cat-groceries", false)
            assertEquals(
                "cat-dining",
                db
                    .categoryRules()
                    .allCategories()
                    .filter { it.parentId == null }
                    .minBy { it.sortOrder }
                    .id,
            )
        }

    // ---- REQ-EXP-061

    @Test
    fun `REQ-EXP-061 the merchant key is normalised and digit free, and empty keys get no rule`() =
        runTest {
            assertEquals("talabat eg", MerchantKey.of("TALABAT EG"))
            assertEquals("talabat 123", MerchantKey.of("  Talabat   123 "))
            assertEquals("3m", MerchantKey.of("3M"))
            assertEquals("7-eleven", MerchantKey.of("7-Eleven"))
            assertEquals(MerchantKey.of("طلبات"), MerchantKey.of("طَلبات"))

            assertEquals("", MerchantKey.of("   "))
            assertEquals("", MerchantKey.of(null))
            assertEquals(RuleResult.EmptyKey, rules().addRule("   ", "cat-dining"))
            assertEquals(RuleResult.NoCategory, rules().addRule("Talabat", "no-such"))
            assertTrue(
                db
                    .merchantRules()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    private fun pipeline(rulesDb: AppDatabase = db) =
        SmsPipeline(
            rulesDb,
            MemorySettings(),
            MemoryNotifications(),
            clock,
            MemoryGate(),
            FixedKey,
            object : eg.masroof.ingest.CapturedNotifier {
                override fun captured(item: eg.masroof.data.db.CapturedTxEntity) = Unit

                override fun cancel(localId: String) = Unit

                override fun cancelAll() = Unit
            },
            signal = signal,
        )

    private fun sms(body: String) = IncomingMessage(CaptureSource.SMS, "CIB", body, Instant.parse("2026-10-15T08:30:00Z").toEpochMilli())

    @Test
    fun `REQ-EXP-061 a rule files a new SMS capture and changes nothing but the category`() =
        runTest {
            val plain = "Your card ending 4512 was charged EGP 1.00 at AMAZON"
            assertEquals(Disposition.RECORDED, pipeline().accept(sms(plain)))
            val before = db.expenses().all().single()
            assertNull("no rule yet, no category", before.categoryId)
            assertTrue(rules().addRule("amazon", "cat-electronics") is RuleResult.Saved)
            assertEquals(Disposition.RECORDED, pipeline().accept(sms("Your card ending 4512 was charged EGP 2.00 at AMAZON")))
            val after = db.expenses().all().single { it.id != before.id }
            assertEquals("cat-electronics", after.categoryId)
            assertEquals(200L, after.amountMinor)
            assertEquals("EXPENSE", after.type)
            assertEquals("EGP", after.currency)
            assertNull(after.accountId)
            assertEquals("AMAZON", after.merchant)
        }

    @Test
    fun `REQ-EXP-061 a rule files a manual entry with no chosen category, and a chosen category always wins`() =
        runTest {
            rules().addRule("Talabat", "cat-dining")
            val repo = DefaultExpenseRepository(db, clock, Counter(), signal)
            val today = LocalDate.of(2026, 10, 15)
            val filed = repo.add(NewExpense(Money(9_900), today, null, null, null, merchant = "TALABAT"))
            val chosen = repo.add(NewExpense(Money(9_900), today, "cat-groceries", null, null, merchant = "Talabat"))
            val noMerchant = repo.add(NewExpense(Money(9_900), today, null, null, null))
            assertEquals("cat-dining", db.expenses().find(filed)!!.categoryId)
            assertEquals(9_900L, db.expenses().find(filed)!!.amountMinor)
            assertEquals("cat-groceries", db.expenses().find(chosen)!!.categoryId)
            assertNull(db.expenses().find(noMerchant)!!.categoryId)
        }

    @Test
    fun `REQ-EXP-061 applying to past entries changes exactly the live entries with that key, in one go, and Undo restores them`() =
        runTest {
            insertEntry("a", "Talabat", "cat-groceries")
            insertEntry("b", "TALABAT", null)
            insertEntry("c", " tAlAbAt ", "cat-health", amount = 777)
            insertEntry("gone", "Talabat", "cat-groceries", deleted = true)
            insertEntry("other", "Noon", "cat-groceries")
            insertEntry("done", "Talabat", "cat-dining")
            val saved = rules().addRule("Talabat", "cat-dining") as RuleResult.Saved
            assertEquals(3, saved.pastEntries)
            assertEquals("talabat", saved.merchantKey)
            val change = rules().applyToPast(saved.merchantKey, "cat-dining")
            assertEquals(3, change.count)
            for (id in listOf("a", "b", "c")) assertEquals(id, "cat-dining", db.expenses().find(id)!!.categoryId)
            assertEquals(777L, db.expenses().find("c")!!.amountMinor)
            assertEquals("cat-groceries", db.expenses().find("gone")!!.categoryId)
            assertEquals("cat-groceries", db.expenses().find("other")!!.categoryId)
            rules().undo(change)
            assertEquals("cat-groceries", db.expenses().find("a")!!.categoryId)
            assertNull(db.expenses().find("b")!!.categoryId)
            assertEquals("cat-health", db.expenses().find("c")!!.categoryId)
            assertEquals("cat-dining", db.expenses().find("done")!!.categoryId)
        }

    @Test
    fun `REQ-EXP-061 one rule per merchant, a new category replaces the old rule, and delete removes it`() =
        runTest {
            val first = rules().addRule("Talabat", "cat-dining") as RuleResult.Saved
            val second = rules().addRule("TALABAT", "cat-groceries") as RuleResult.Saved
            val all = db.merchantRules().observeAll().first()
            assertEquals(1, all.size)
            assertEquals("cat-groceries", all.single().categoryId)
            assertTrue(rules().hasRule("talabat", "cat-groceries"))
            rules().delete(second.ruleId)
            assertTrue(
                db
                    .merchantRules()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertNotNull(first.ruleId)
        }

    // ---- the Edit prompt

    @Test
    fun `REQ-EXP-061 changing the category of an entry with a merchant offers the rule, no merchant or no change offers nothing`() =
        runTest {
            insertEntry("t1", "TALABAT EG", "cat-groceries")
            insertEntry("t2", null, "cat-groceries")
            val editor = DefaultExpenseEditor(db, signal, clock, EraseLock())

            suspend fun TestScope.editVm(id: String): EditExpenseViewModel {
                val vm = EditExpenseViewModel(editor, SavedStateHandle(mapOf("id" to id)))
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                vm.state.first { it.form.loaded }
                return vm
            }
            val vm = editVm("t1")
            vm.onCategory("cat-dining")
            vm.save()
            val form = vm.state.first { it.form.done }.form
            assertEquals(RuleOffer("TALABAT EG", "cat-dining", "t1"), form.ruleOffer)

            val noChange = editVm("t1")
            noChange.onMerchant("TALABAT EG 2")
            noChange.save()
            assertNull(
                noChange.state
                    .first { it.form.done }
                    .form.ruleOffer,
            )

            val noMerchant = editVm("t2")
            noMerchant.onCategory("cat-dining")
            noMerchant.save()
            assertNull(
                noMerchant.state
                    .first { it.form.done }
                    .form.ruleOffer,
            )
        }

    @Test
    fun `REQ-EXP-061 the prompt asks, stores the rule, offers the past entries, applies them and Undo puts them back`() =
        runTest {
            insertEntry("t1", "Talabat", "cat-dining")
            repeat(2) { insertEntry("p$it", "Talabat", "cat-groceries") }
            val vm = RulePromptViewModel(rules(), categories())
            vm.begin(RuleOffer("Talabat", "cat-dining", "t1"))
            assertEquals(RulePromptStage.AskRule, vm.stage.first { it != RulePromptStage.Idle })
            vm.yes()
            assertEquals(RulePromptStage.AskPast("talabat", 2), vm.stage.first { it is RulePromptStage.AskPast })
            assertEquals(
                1,
                db
                    .merchantRules()
                    .observeAll()
                    .first()
                    .size,
            )
            vm.yes()
            assertEquals(RulePromptStage.Changed(2), vm.stage.first { it is RulePromptStage.Changed })
            assertEquals("cat-dining", db.expenses().find("p0")!!.categoryId)
            vm.undo()
            vm.finished.first { it }
            assertEquals("cat-groceries", db.expenses().find("p0")!!.categoryId)
            assertEquals(
                "the rule itself stays",
                1,
                db
                    .merchantRules()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun `REQ-EXP-061 declining stores nothing, and an existing rule is not asked again`() =
        runTest {
            insertEntry("t1", "Talabat", "cat-dining")
            val declined = RulePromptViewModel(rules(), categories())
            declined.begin(RuleOffer("Talabat", "cat-dining", "t1"))
            declined.no()
            assertTrue(declined.finished.value)
            assertTrue(
                db
                    .merchantRules()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )

            rules().addRule("Talabat", "cat-dining")
            val again = RulePromptViewModel(rules(), categories())
            again.begin(RuleOffer("TALABAT", "cat-dining", "t1"))
            again.finished.first { it }
            assertEquals(RulePromptStage.Idle, again.stage.value)

            insertEntry("only", "Solo", "cat-dining")
            val noPast = RulePromptViewModel(rules(), categories())
            noPast.begin(RuleOffer("Solo", "cat-dining", "only"))
            noPast.stage.first { it == RulePromptStage.AskRule }
            noPast.yes()
            // no past entries: no second question
            noPast.finished.first { it }
            assertEquals(RulePromptStage.Idle, noPast.stage.value)
        }
}
