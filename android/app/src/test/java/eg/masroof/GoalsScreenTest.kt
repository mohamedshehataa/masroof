package eg.masroof

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.data.db.GoalMovementEntity
import eg.masroof.data.goals.GoalMath
import eg.masroof.ui.goals.GoalCardUi
import eg.masroof.ui.goals.GoalDetailActions
import eg.masroof.ui.goals.GoalDetailContent
import eg.masroof.ui.goals.GoalDetailState
import eg.masroof.ui.goals.GoalFormActions
import eg.masroof.ui.goals.GoalFormContent
import eg.masroof.ui.goals.GoalFormState
import eg.masroof.ui.goals.GoalsContent
import eg.masroof.ui.goals.GoalsListState
import eg.masroof.ui.goals.GoalsTotals
import eg.masroof.ui.goals.MoneyDialogState
import eg.masroof.ui.goals.MoneyError
import eg.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

private fun worked(): GoalMath.Progress =
    GoalMath.progress(
        GoalMath.Input(
            targetMinor = 85_000_000L,
            savedMinor = 13_500_000L,
            createdOn = LocalDate.parse("2026-01-01"),
            dueOn = LocalDate.parse("2028-04-01"),
            today = LocalDate.parse("2026-11-08"),
            monthStartDay = 1,
        ),
    )

private fun carCard(currency: String = "EGP") = GoalCardUi("car", "New car", "🚗", currency, false, worked())

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GoalsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val noActions = GoalDetailActions({}, {}, {}, {}, {})

    private fun detail(
        dialog: MoneyDialogState? = null,
        celebrate: Boolean = false,
    ) = compose.setContent {
        MasroofTheme {
            GoalDetailContent(
                GoalDetailState(
                    loading = false,
                    card = carCard(),
                    history = listOf(GoalMovementEntity("m1", "car", 500_000L, 1_793_000_000_000L, "salary")),
                    steps = listOf(500_000L),
                ),
                noActions,
                dialog,
                celebrate,
                { _, _, _ -> },
                {},
                {},
            )
        }
    }

    @Test
    fun `REQ-AN-167 the list shows each goal and total saved of total target with the other currency hint`() {
        compose.setContent {
            MasroofTheme {
                GoalsContent(
                    GoalsListState(
                        loading = false,
                        cards = listOf(carCard()),
                        totals = GoalsTotals(13_500_000L, 85_000_000L, 1588, otherCurrencyCount = 1),
                    ),
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("New car").assertIsDisplayed()
        compose.onAllNodesWithText("135,000 EGP of 850,000 EGP").assertCountEquals(2)
        compose.onNodeWithText("1 goal in another currency is not included").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-056 the empty state offers a new goal and the error state offers retry`() {
        var created = 0
        compose.setContent {
            MasroofTheme {
                GoalsContent(GoalsListState(loading = false), {}, { created++ }, {})
            }
        }
        compose.onNodeWithText("Save towards something").assertIsDisplayed()
        compose.onAllNodesWithText("New goal").assertCountEquals(2)
        compose.onAllNodesWithText("New goal")[1].assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, created)
    }

    @Test
    fun `REQ-EXP-056 the error state shows Retry and calls back`() {
        var retried = 0
        compose.setContent { MasroofTheme { GoalsContent(GoalsListState(loading = false, error = true), {}, {}, { retried++ }) } }
        compose.onNodeWithText("Couldn't load your goals").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `REQ-AN-167 the detail shows 510 days to go and needs 42,059 EGP a month`() {
        detail()
        compose.onNodeWithText("510 days to go").assertIsDisplayed()
        compose.onNodeWithText("Needs 42,059 EGP a month to make it").assertIsDisplayed()
        compose.onNodeWithText("135,000 EGP of 850,000 EGP").assertIsDisplayed()
        compose.onNodeWithText("16%").assertIsDisplayed()
        compose.onNodeWithText("Add money").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Take out").assertIsDisplayed()
        compose.onNodeWithText("Money you've added").assertIsDisplayed()
        compose.onNodeWithText("Added 5,000 EGP").assertExists()
    }

    @Test
    fun `REQ-EXP-056 the take out dialog shows how much the goal holds`() {
        detail(dialog = MoneyDialogState(take = true, error = MoneyError.ONLY_HAVE, onlyHaveMinor = 500_000L))
        compose.onNodeWithText("You only have 5,000 EGP in this goal").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-167 reaching the goal shows the celebration once`() {
        detail(celebrate = true)
        compose.onNodeWithText("🎉 Goal reached!").assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-056 delete asks first`() {
        detail()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete this goal?").assertExists()
    }

    @Test
    fun `REQ-EXP-056 the form shows the emoji grid and field errors`() {
        compose.setContent {
            MasroofTheme {
                GoalFormContent(
                    GoalFormState(nameError = true, targetError = MoneyError.AMOUNT, dateError = true),
                    GoalFormActions({}, {}, {}, {}, {}),
                )
            }
        }
        compose.onNodeWithText("New goal").assertIsDisplayed()
        compose.onNodeWithText("Enter a name").assertIsDisplayed()
        compose.onNodeWithText("Enter an amount above zero").assertExists()
        compose.onNodeWithText("Use the format yyyy-mm-dd").assertExists()
        compose.onNodeWithText("Pick an emoji").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar")
class GoalsScreenArabicTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-AN-167 Arabic mirrors the layout and shows Arabic-Indic digits`() {
        var direction: LayoutDirection? = null
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                GoalDetailContent(
                    GoalDetailState(loading = false, card = carCard()),
                    GoalDetailActions({}, {}, {}, {}, {}),
                    null,
                    false,
                    { _, _, _ -> },
                    {},
                    {},
                )
            }
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("فاضل ٥١٠ أيام").assertIsDisplayed()
        compose.onNodeWithText("محتاج ٤٢٬٠٥٩ ج.م في الشهر عشان توصل").assertIsDisplayed()
        compose.onNodeWithText("١٣٥٬٠٠٠ ج.م من ٨٥٠٬٠٠٠ ج.م").assertIsDisplayed()
    }
}
