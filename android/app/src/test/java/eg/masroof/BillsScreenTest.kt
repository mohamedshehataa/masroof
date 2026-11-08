package eg.masroof

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import eg.masroof.data.bills.BillMath
import eg.masroof.data.bills.PaymentChoice
import eg.masroof.data.db.ExpenseEntity
import eg.masroof.ui.bills.AddPlanContent
import eg.masroof.ui.bills.AddPlanForm
import eg.masroof.ui.bills.AddPlanState
import eg.masroof.ui.bills.BillsActions
import eg.masroof.ui.bills.BillsContent
import eg.masroof.ui.bills.BillsUiState
import eg.masroof.ui.bills.FormResult
import eg.masroof.ui.bills.PlanDetailActions
import eg.masroof.ui.bills.PlanDetailContent
import eg.masroof.ui.bills.PlanDetailState
import eg.masroof.ui.bills.parse
import eg.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

private val TODAY: LocalDate = LocalDate.parse("2026-11-08")

private fun stateOf(
    vararg specs: Pair<String, Int>,
    allowed: Boolean = true,
): BillsUiState {
    val plans =
        listOf(
            BillsFixtures.plan("a", 325_000, 18, "2026-03-05", title = "iPhone 17 Pro"),
            BillsFixtures.plan("b", 145_000, 12, "2026-06-10", provider = "HALAN", title = "Laptop"),
            BillsFixtures.plan("c", 210_000, 6, "2026-09-20", provider = "SOUHOOLA", title = "AC"),
        ).filter { p -> specs.any { it.first == p.id } }
    val paid = specs.toMap()
    val payments = plans.flatMap { BillsFixtures.payments(it, paid.getValue(it.id)) }
    return BillsUiState(
        loaded = true,
        overview = BillMath.overview(plans, payments, TODAY),
        today = TODAY,
        calendarMonth = YearMonth.of(2026, 11),
        calendar = BillMath.calendar(plans, payments, YearMonth.of(2026, 11)),
        notificationsAllowed = allowed,
    )
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class BillsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var added = 0
    private var opened: String? = null
    private var allowTaps = 0
    private var shifts = mutableListOf<Int>()
    private var direction: LayoutDirection? = null

    private fun show(state: BillsUiState) =
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                BillsContent(
                    state,
                    BillsActions(
                        onAdd = { added++ },
                        onOpenPlan = { opened = it },
                        onShiftMonth = { shifts += it },
                        onAllowNotifications = { allowTaps++ },
                    ),
                )
            }
        }

    @Test
    fun `REQ-AN-166 with no plans the empty state shows one example card and Add installment`() {
        show(BillsUiState(loaded = true, overview = BillMath.overview(emptyList(), emptyList(), TODAY), today = TODAY))
        compose.onNodeWithText("No installments yet").assertIsDisplayed()
        compose.onNodeWithText("Example").assertIsDisplayed()
        compose.onNodeWithText("valU - iPhone 17 Pro").assertIsDisplayed()
        compose.onAllNodesWithText("Add installment").onFirst().performClick()
        assertEquals(1, added)
        compose.onAllNodesWithText("Add installment")[1].performClick()
        assertEquals(2, added)
    }

    @Test
    fun `REQ-AN-166 loading shows a skeleton`() {
        show(BillsUiState(loaded = false))
        compose.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-166 the overview shows the monthly total, remaining, the drop and each plan's progress`() {
        show(stateOf("a" to 8, "b" to 5, "c" to 2))
        // 3,250 + 1,450 + 2,100 fall due in November
        compose.onNodeWithText("6,800 EGP").assertIsDisplayed()
        compose.onNodeWithText("Drops to 4,700 EGP in 4 months").assertIsDisplayed()
        compose.onNodeWithText("8 of 18 paid").assertIsDisplayed()
        compose.onNodeWithText("5 of 12 paid").assertIsDisplayed()
        compose.onNodeWithText("Ends Aug 2027").assertIsDisplayed()
        compose.onNodeWithText("valU - iPhone 17 Pro").assertIsDisplayed()
        compose.onAllNodesWithText("Halan - Laptop").onLast().performClick()
        assertEquals("b", opened)
    }

    @Test
    fun `REQ-AN-166 payments in the next 14 days appear under Coming up`() {
        // plan b's 6th payment is on 2026-11-10 with 5 of 12 paid
        show(stateOf("b" to 5))
        compose.onNodeWithText("Coming up").assertIsDisplayed()
        compose.onNodeWithText("10 Nov").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-166 the calendar view lists the due days of the month`() {
        show(stateOf("a" to 8, "b" to 5))
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithContentDescription("Day 5, 1 payments").assertIsDisplayed()
        compose.onNodeWithContentDescription("Day 10, 1 payments").assertIsDisplayed()
        compose.onNodeWithContentDescription("Day 6, 0 payments").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Previous").performClick()
        assertEquals(listOf(1, -1), shifts)
    }

    @Test
    fun `REQ-AND-148 with notifications denied Bills shows a hint with an Allow button`() {
        show(stateOf("a" to 8, allowed = false))
        compose.onNodeWithText("Allow notifications to get a reminder before each payment.").assertIsDisplayed()
        compose.onNodeWithText("Allow").performClick()
        assertEquals(1, allowTaps)
    }

    @Test
    fun `REQ-AND-148 with notifications allowed there is no hint`() {
        show(stateOf("a" to 8))
        compose.onAllNodesWithText("Allow").assertCountEquals(0)
    }

    @Test
    @Config(sdk = [34], qualifiers = "+ar")
    fun `REQ-AN-166 in Arabic the screen is right to left with Arabic-Indic digits and Arabic labels`() {
        compose.setContent {
            MasroofTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    direction = LocalLayoutDirection.current
                    BillsContent(stateOf("a" to 8, "b" to 5, "c" to 2), BillsActions({}, {}, {}, {}))
                }
            }
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الأقساط").assertIsDisplayed()
        compose.onNodeWithText("٦٬٨٠٠ ج.م").assertIsDisplayed()
        compose.onNodeWithText("اتدفع ٨ من ١٨").assertIsDisplayed()
        compose.onNodeWithText("فاليو - iPhone 17 Pro").assertIsDisplayed()
    }

    // --- plan detail ----------------------------------------------------------------------------------------

    private var recorded: PaymentChoice? = null
    private var recordStarts = 0
    private var completes = 0
    private var deletes = 0
    private var collects = 0
    private var undos = 0

    private fun actions() =
        PlanDetailActions(
            onRecordStart = { recordStarts++ },
            onRecordDismiss = {},
            onRecord = { recorded = it },
            onCollect = { collects++ },
            onUndo = { undos++ },
            onDismissUndo = {},
            onComplete = { completes++ },
            onDelete = { deletes++ },
        )

    private fun detail(
        paid: Int,
        candidates: List<ExpenseEntity>? = null,
        status: String = "ACTIVE",
        token: eg.masroof.data.bills.UndoToken? = null,
    ): PlanDetailState {
        val plan = BillsFixtures.plan("a", 325_000, 18, "2026-03-05", title = "iPhone 17 Pro", status = status)
        return PlanDetailState(
            loaded = true,
            progress = BillMath.progressOf(plan, BillsFixtures.payments(plan, paid)),
            today = TODAY,
            candidates = candidates,
            undo = token,
        )
    }

    private fun showDetail(state: PlanDetailState) = compose.setContent { MasroofTheme { PlanDetailContent(state, actions()) } }

    @Test
    fun `REQ-EXP-054 a plan with 7 of 18 paid offers Record payment 8 of 18`() {
        showDetail(detail(paid = 7))
        compose.onNodeWithText("iPhone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Record payment (8/18)").performScrollTo().performClick()
        assertEquals(1, recordStarts)
    }

    @Test
    fun `REQ-EXP-054 the record dialog offers link candidates, a new entry or only marking paid`() {
        val candidate = ExpenseEntity("e1", 325_000, "EGP", BillsFixtures.noon("2026-10-05"), "valU", null, null, null, pending = false)
        showDetail(detail(paid = 7, candidates = listOf(candidate)))
        compose.onNodeWithText("Link an entry you already have").assertIsDisplayed()
        compose.onNodeWithText("5 Oct  3,250 EGP", substring = true).performClick()
        assertEquals(PaymentChoice.LinkExisting("e1"), recorded)
        compose.onNodeWithText("Create a new entry").performClick()
        assertEquals(PaymentChoice.CreateEntry, recorded)
        compose.onNodeWithText("Just mark as paid").performClick()
        assertEquals(PaymentChoice.MarkOnly, recorded)
    }

    @Test
    fun `REQ-EXP-054 with no candidates the dialog says so and still offers a new entry`() {
        showDetail(detail(paid = 7, candidates = emptyList()))
        compose.onNodeWithText("No existing entry matches this payment.").assertIsDisplayed()
        compose.onNodeWithText("Create a new entry").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-054 when every payment is paid Record payment is hidden and the plan shows Completed`() {
        showDetail(detail(paid = 18))
        compose.onAllNodesWithText("Completed").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Record payment", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Mark as complete").assertCountEquals(0)
    }

    @Test
    fun `REQ-EXP-054 Mark as complete and Delete ask first`() {
        showDetail(detail(paid = 7))
        compose.onNodeWithText("Mark as complete").performScrollTo().performClick()
        assertEquals(0, completes)
        compose.onNodeWithText("Mark as complete?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, completes)
        compose.onNodeWithText("Mark as complete").performScrollTo().performClick()
        compose.onNodeWithText("Mark complete").performClick()
        assertEquals(1, completes)
        compose.onNodeWithText("Delete plan").performScrollTo().performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Delete this plan?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun `REQ-EXP-054 the detail shows monthly, remaining and the payment due countdown`() {
        // payment 8 of plan a fell due on 2026-10-05: with 7 paid it is overdue; with 8 paid the next is 2026-11-05 (3 days ago)
        showDetail(detail(paid = 9))
        compose.onAllNodesWithText("3,250 EGP").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Remaining").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("9 of 18 paid").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-054 after recording a payment Undo is offered`() {
        val plan = BillsFixtures.plan("a", 325_000, 18, "2026-03-05")
        val payment = BillsFixtures.payments(plan, 0)[0]
        showDetail(
            detail(
                paid = 1,
                token =
                    eg.masroof.data.bills
                        .UndoToken(plan, payment, null),
            ),
        )
        compose.onNodeWithText("Payment recorded").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        assertEquals(1, undos)
    }

    @Test
    fun `REQ-EXP-055 a gam3eya shows when the collection comes and Record collection`() {
        val plan =
            BillsFixtures.plan(
                "g",
                200_000,
                10,
                "2026-11-01",
                provider = "GAM3EYA",
                title = "Office",
                payoutAt = "2027-02-01",
                payoutMinor = 2_000_000,
            )
        val state = PlanDetailState(loaded = true, progress = BillMath.progressOf(plan, BillsFixtures.payments(plan, 0)), today = TODAY)
        compose.setContent { MasroofTheme { PlanDetailContent(state, actions()) } }
        compose.onNodeWithText("You collect 20,000 EGP on 1 Feb").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Record collection").performScrollTo().performClick()
        assertEquals(1, collects)
    }

    @Test
    fun `REQ-EXP-054 a missing plan says so`() {
        compose.setContent { MasroofTheme { PlanDetailContent(PlanDetailState(loaded = true, progress = null), actions()) } }
        compose.onNodeWithText("This plan no longer exists.").assertIsDisplayed()
    }

    // --- add form -------------------------------------------------------------------------------------------

    private var form by mutableStateOf(AddPlanState(form = AddPlanForm(firstDate = "2026-11-08"), today = TODAY))
    private var saved: eg.masroof.data.bills.NewPlan? = null

    private fun showForm() =
        compose.setContent {
            MasroofTheme {
                AddPlanContent(
                    form,
                    onEdit = { change -> form = form.copy(form = change(form.form), errors = emptySet()) },
                    onSave = {
                        when (val r = form.form.parse(TODAY)) {
                            is FormResult.Errors -> form = form.copy(errors = r.fields)
                            is FormResult.Ok -> saved = r.plan
                        }
                    },
                )
            }
        }

    @Test
    fun `REQ-EXP-053 saving an empty form shows field errors and stores nothing`() {
        showForm()
        compose.onNodeWithText("Save plan").performScrollTo().performClick()
        compose.onNodeWithText("Enter a name").assertIsDisplayed()
        compose.onNodeWithText("Enter an amount above 0 and up to 10,000,000,000").assertIsDisplayed()
        compose.onNodeWithText("Enter 1 to 120").assertIsDisplayed()
        assertEquals(null, saved)
    }

    @Test
    fun `REQ-EXP-053 the form offers every provider and every what-is-it-for chip`() {
        showForm()
        listOf(
            "valU",
            "Halan",
            "Souhoola",
            "Gam3eya",
            "Money Fellows",
            "Contact",
            "Aman",
            "Forsa",
            "Sympl",
            "Bank / credit card",
            "Another store",
            "No provider",
            "Phone",
            "Car",
            "Laptop",
            "Furniture",
            "AC",
            "Fridge",
            "TV",
            "Personal loan",
        ).forEach {
            compose
                .onAllNodesWithText(it)
                .onFirst()
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun `REQ-EXP-053 a chip fills the name and a past first payment asks how many are already paid`() {
        form = form.copy(form = AddPlanForm(firstDate = "2026-03-05", count = "18", monthly = "3,250"))
        showForm()
        compose.onNodeWithText("Phone").performScrollTo().performClick()
        assertEquals("Phone", form.form.title)
        compose.onNodeWithText("How many are already paid?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last payment: 5 Aug").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save plan").performScrollTo().performClick()
        assertTrue(saved != null)
        assertEquals(9, saved!!.alreadyPaid)
        assertEquals(325_000L, saved!!.monthlyMinor)
    }

    @Test
    fun `REQ-EXP-055 choosing Gam3eya swaps the duration for members, turn and collection amount`() {
        showForm()
        compose
            .onAllNodesWithText("Gam3eya")
            .onFirst()
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Number of members").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your collection turn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Monthly share (EGP)").assertIsDisplayed()
        compose.onNodeWithText("Save plan").performScrollTo().performClick()
        compose.onNodeWithText("The turn must be between 1 and the number of members").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "+ar")
    fun `REQ-EXP-053 the add form is available in Arabic`() {
        compose.setContent {
            MasroofTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AddPlanContent(form, onEdit = {}, onSave = {})
                }
            }
        }
        compose.onAllNodesWithText("ضيف قسط").onFirst().assertIsDisplayed()
        compose.onNodeWithText("حالا").performScrollTo().assertIsDisplayed()
    }
}
