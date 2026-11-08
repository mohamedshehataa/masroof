package eg.masroof

import androidx.compose.ui.platform.LocalLayoutDirection
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
import eg.masroof.data.bills.Cadence
import eg.masroof.data.bills.EntryFact
import eg.masroof.data.bills.KIND_BILL
import eg.masroof.data.bills.KIND_INCOME
import eg.masroof.data.bills.PriceNotice
import eg.masroof.data.bills.SubscriptionsData
import eg.masroof.data.bills.Suggestion
import eg.masroof.ui.bills.BillsTab
import eg.masroof.ui.bills.SubField
import eg.masroof.ui.bills.SubItem
import eg.masroof.ui.bills.SubscriptionDetailActions
import eg.masroof.ui.bills.SubscriptionDetailContent
import eg.masroof.ui.bills.SubscriptionDetailState
import eg.masroof.ui.bills.SubscriptionForm
import eg.masroof.ui.bills.SubscriptionFormContent
import eg.masroof.ui.bills.SubscriptionFormState
import eg.masroof.ui.bills.SubscriptionsActions
import eg.masroof.ui.bills.SubscriptionsContent
import eg.masroof.ui.bills.SubscriptionsUiState
import eg.masroof.ui.bills.subscriptionsState
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

private val NOW: LocalDate = LocalDate.parse("2026-11-08")

private fun fact(
    id: String,
    merchant: String,
    amount: Long,
    date: String,
) = EntryFact(id, merchant.lowercase(), merchant, amount, "EGP", LocalDate.parse(date))

private fun worked(
    notices: List<PriceNotice> = emptyList(),
    withSuggestion: Boolean = true,
): SubscriptionsUiState {
    val rows =
        listOf(
            subRow("n", "Netflix", 19_900, next = "2026-11-11", merchantKey = "netflix"),
            subRow("i", "iCloud", 4_900, next = "2026-11-20"),
            subRow("g", "Gym", 600_000, Cadence.YEARLY, next = "2027-01-10"),
            subRow("w", "Water bill", 7_500, Cadence.WEEKLY, next = "2026-11-12", kind = KIND_BILL),
            subRow("s", "Salary", 2_000_000, next = "2026-11-25", kind = KIND_INCOME),
        )
    val entries =
        listOf(fact("p", "Netflix", 19_900, "2026-10-31")) +
            if (withSuggestion) {
                listOf(
                    fact("a", "Spotify", 12_900, "2026-09-05"),
                    fact("b", "Spotify", 12_900, "2026-10-05"),
                    fact("c", "Spotify", 12_900, "2026-11-04"),
                )
            } else {
                emptyList()
            }
    return subscriptionsState(SubscriptionsData(rows, entries, emptySet(), notices), NOW, YearMonth.of(2026, 11), 1)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class SubscriptionsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var added = 0
    private var opened: String? = null
    private var suggested: Suggestion? = null
    private var dismissedKey: String? = null
    private var dismissedNotice: String? = null

    private fun show(
        state: SubscriptionsUiState,
        tab: BillsTab = BillsTab.SUBSCRIPTIONS,
    ) = compose.setContent {
        MasroofTheme {
            SubscriptionsContent(
                state,
                tab,
                SubscriptionsActions(
                    onAdd = { added++ },
                    onOpen = { opened = it },
                    onShiftMonth = {},
                    onAddSuggestion = { suggested = it },
                    onDismissSuggestion = { dismissedKey = it },
                    onDismissNotice = { dismissedNotice = it },
                ),
            )
        }
    }

    @Test
    fun `REQ-AN-170 the card shows 1,073 a month, flips to 12,876 a year and counts four active`() {
        show(worked())
        compose.onNodeWithText("1,073 EGP").assertIsDisplayed()
        compose.onNodeWithText("4 active").assertIsDisplayed()
        compose.onNodeWithText("Per year").performClick()
        compose.onNodeWithText("12,876 EGP").assertIsDisplayed()
        compose.onNodeWithText("Per month").performClick()
        compose.onNodeWithText("1,073 EGP").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-170 the list is sorted by next renewal with the days left and the last payment`() {
        show(worked())
        compose.onNodeWithText("Renews 11 Nov, in 3 days").assertIsDisplayed()
        compose.onNodeWithText("Paid 31 Oct").assertIsDisplayed()
        compose.onNodeWithText("Renews 12 Nov, in 4 days").assertIsDisplayed()
        compose.onAllNodesWithText("Netflix", substring = true).onFirst().performClick()
        assertEquals("n", opened)
        val names = worked().subscriptions.map { it.row.name }
        assertEquals(listOf("Netflix", "Water bill", "iCloud", "Gym"), names)
    }

    @Test
    fun `REQ-AN-170 income is listed separately under Coming in, never in the totals`() {
        val state = worked()
        assertEquals(listOf("Salary"), state.income.map { it.row.name })
        assertEquals(1_287_600L, state.totals.perYearMinor)
        show(state, BillsTab.COMING_IN)
        compose.onNodeWithText("20,000 EGP").assertIsDisplayed()
        compose.onNodeWithText("17 days to payday").assertIsDisplayed()
        compose.onNodeWithText("Salary on 25 Nov").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-170 the calendar shows the renewals of the month`() {
        val state = worked()
        assertTrue(state.calendar.any { it.title == "Netflix" && it.date == LocalDate.parse("2026-11-11") })
        assertEquals(3, state.calendar.count { it.title == "Water bill" })
        show(state)
        compose.onNodeWithText("Calendar").performClick()
        compose.onAllNodesWithText("Water bill").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Nothing renews this month.").assertDoesNotExist()
    }

    @Test
    fun `REQ-AN-172 a suggestion card offers Add and Not a subscription`() {
        show(worked())
        compose.onNodeWithText("Looks like a monthly payment: Spotify about 129 EGP").assertIsDisplayed()
        compose.onAllNodesWithText("Add").onLast().performClick()
        assertEquals("spotify", suggested?.key)
        assertEquals(12_900L, suggested?.amountMinor)
        assertEquals(LocalDate.parse("2026-12-04"), suggested?.nextRenewal)
        compose.onNodeWithText("Not a subscription").performClick()
        assertEquals("spotify", dismissedKey)
    }

    @Test
    fun `REQ-AN-172 a dismissed key or an existing subscription removes the suggestion`() {
        val rows = listOf(subRow("n", "Spotify", 12_900, next = "2026-11-20"))
        val entries = listOf("2026-09-05", "2026-10-05", "2026-11-04").mapIndexed { i, d -> fact("e$i", "Spotify", 12_900, d) }
        assertTrue(
            subscriptionsState(
                SubscriptionsData(rows, entries, emptySet(), emptyList()),
                NOW,
                YearMonth.of(2026, 11),
                1,
            ).suggestions.isEmpty(),
        )
        assertTrue(
            subscriptionsState(
                SubscriptionsData(emptyList(), entries, setOf("spotify"), emptyList()),
                NOW,
                YearMonth.of(2026, 11),
                1,
            ).suggestions.isEmpty(),
        )
        assertEquals(
            1,
            subscriptionsState(
                SubscriptionsData(emptyList(), entries, emptySet(), emptyList()),
                NOW,
                YearMonth.of(2026, 11),
                1,
            ).suggestions.size,
        )
    }

    @Test
    fun `REQ-AN-171 a price change shows once and is dismissed with Got it`() {
        show(worked(notices = listOf(PriceNotice("n", "Netflix", 19_900, 21_000, "EGP")), withSuggestion = false))
        compose.onNodeWithText("Netflix went up from 199 EGP to 210 EGP").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        assertEquals("n", dismissedNotice)
    }

    @Test
    fun `REQ-AN-170 empty and loading states`() {
        show(SubscriptionsUiState(loaded = true, today = NOW, calendarMonth = YearMonth.of(2026, 11)))
        compose.onNodeWithText("No subscriptions yet").assertIsDisplayed()
        compose.onAllNodesWithText("Add").onLast().performClick()
        assertEquals(1, added)
    }

    @Test
    fun `REQ-AN-170 loading shows a skeleton`() {
        show(SubscriptionsUiState(loaded = false))
        compose.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-172 without income the empty Coming in tab still counts to the month start`() {
        val state = subscriptionsState(SubscriptionsData(emptyList(), emptyList(), emptySet(), emptyList()), NOW, YearMonth.of(2026, 11), 1)
        show(state, BillsTab.COMING_IN)
        compose.onNodeWithText("No income added yet").assertIsDisplayed()
        compose.onNodeWithText("23 days to payday").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "+ar")
    fun `REQ-AN-170 in Arabic the screen is right to left with Arabic-Indic digits`() {
        var direction: LayoutDirection? = null
        compose.setContent {
            MasroofTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    direction = LocalLayoutDirection.current
                    SubscriptionsContent(worked(), BillsTab.SUBSCRIPTIONS, SubscriptionsActions({}, {}, {}, {}, {}, {}))
                }
            }
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("الاشتراكات والفواتير").assertIsDisplayed()
        compose.onNodeWithText("بتكلفك كام").assertIsDisplayed()
        compose.onNodeWithText("٤ شغّال").assertIsDisplayed()
        compose.onNodeWithText("١٬٠٧٣ ج.م").assertIsDisplayed()
    }

    // --- add / edit / detail --------------------------------------------------------------------------------

    @Test
    fun `REQ-EXP-062 the form shows the field errors and the built-in services`() {
        compose.setContent {
            MasroofTheme {
                SubscriptionFormContent(
                    SubscriptionFormState(errors = setOf(SubField.NAME, SubField.AMOUNT, SubField.DATE)),
                    onEdit = {},
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText("Enter a name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter a date like 2026-03-05").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Shahid VIP", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-062 picking a service fills the name, and income offers salary and certificate`() {
        val formState = androidx.compose.runtime.mutableStateOf(SubscriptionForm())
        compose.setContent {
            MasroofTheme {
                SubscriptionFormContent(
                    SubscriptionFormState(form = formState.value),
                    onEdit = { formState.value = it(formState.value) },
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText("Netflix", substring = true).performScrollTo().performClick()
        assertEquals("Netflix", formState.value.name)
        compose.onNodeWithText("Recurring income").performScrollTo().performClick()
        compose.onNodeWithText("Certificate interest").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Salary").performScrollTo().performClick()
        assertEquals("Salary", formState.value.name)
        assertTrue(formState.value.isIncome)
    }

    @Test
    fun `REQ-EXP-062 delete asks first, pause and edit act at once`() {
        var deleted = 0
        var edits = 0
        var active: Boolean? = null
        val row = subRow("n", "Netflix", 19_900, next = "2026-11-11", merchantKey = "netflix")
        compose.setContent {
            MasroofTheme {
                SubscriptionDetailContent(
                    SubscriptionDetailState(true, SubItem(row, LocalDate.parse("2026-11-11"), 3, null), NOW),
                    SubscriptionDetailActions(onEdit = { edits++ }, onSetActive = { active = it }, onDelete = { deleted++ }),
                )
            }
        }
        compose.onNodeWithText("Renews 11 Nov, in 3 days").assertIsDisplayed()
        compose.onNodeWithText("2,388 EGP a year").assertIsDisplayed()
        compose.onNodeWithText("Edit").performClick()
        assertEquals(1, edits)
        compose.onNodeWithText("Pause").performClick()
        assertEquals(false, active)
        compose.onNodeWithText("Delete").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Delete this item?").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun `REQ-EXP-062 a missing item shows the not found message`() {
        compose.setContent {
            MasroofTheme { SubscriptionDetailContent(SubscriptionDetailState(loaded = true), SubscriptionDetailActions({}, {}, {})) }
        }
        compose.onNodeWithText("This item no longer exists.").assertIsDisplayed()
    }
}
