package eg.masroof

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.core.Money
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.ui.home.HomeContent
import eg.masroof.ui.insights.InsightsContent
import eg.masroof.ui.insights.heatLevel
import eg.masroof.ui.insights.toUi
import eg.masroof.ui.insights.weekdayIndex
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.HomeInsight
import eg.masroof.ui.viewmodel.HomeUiState
import eg.masroof.ui.viewmodel.InsightsState
import eg.masroof.ui.viewmodel.LoadStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class InsightsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val data = InsightsFixtures.snapshot().toUi()
    private var picked: InsightsPeriod? = null
    private var retries = 0
    private var direction: LayoutDirection? = null

    private fun show(state: InsightsState) =
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                InsightsContent(state, onPeriod = { picked = it }, onRetry = { retries++ })
            }
        }

    private fun ready(
        status: LoadStatus = LoadStatus.READY,
        period: InsightsPeriod = InsightsPeriod.CURRENT,
    ) = InsightsState(period, data, status)

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `REQ-AN-145 a period with only foreign-currency entries shows zero and the not-included note, not the empty state`() {
        val foreign = InsightsFixtures.emptySnapshot().copy(otherCurrencyCount = 1).toUi()
        show(InsightsState(InsightsPeriod.CURRENT, foreign, LoadStatus.READY))
        compose.onNodeWithText("1 entry in another currency is not included").assertIsDisplayed()
        compose.onAllNodesWithText("Not enough data yet").assertCountEquals(0)
    }

    @Test
    fun `REQ-AND-070 overview card shows the period total, change, average, projection and days left`() {
        show(ready())
        compose.onNodeWithText("Insights").assertIsDisplayed()
        compose.onNodeWithText("Spent this month").assertIsDisplayed()
        compose.onNodeWithText("1,250 EGP").assertIsDisplayed()
        compose.onNodeWithText("Daily average").assertIsDisplayed()
        compose.onNodeWithText("50 EGP").assertIsDisplayed()
        compose.onNodeWithText("Projected month-end").assertIsDisplayed()
        compose.onNodeWithText("1,550 EGP").assertIsDisplayed()
        compose.onNodeWithText("Days left").assertIsDisplayed()
        compose.onNodeWithText("6").assertIsDisplayed()
        compose.onNodeWithText("vs 1,000 EGP in the previous period").assertIsDisplayed()
        compose.onNodeWithText("25%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Spending is up 25% compared with the previous period").assertIsDisplayed()
        compose.onNodeWithText("2 expenses need review").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-070 a finished period hides the projection and days left`() {
        val past = InsightsFixtures.snapshot(daysLeft = 0, pending = 0).toUi()
        show(InsightsState(InsightsPeriod.PREVIOUS, past, LoadStatus.READY))
        compose.onNodeWithText("Spent last month").assertIsDisplayed()
        compose.onNodeWithText("Projected month-end").assertDoesNotExist()
        compose.onNodeWithText("Days left").assertDoesNotExist()
    }

    @Test
    fun `REQ-AND-070 categories are ranked with amount share and change`() {
        show(ready())
        scrollTo("Spending by category")
        compose.onNodeWithText("Spending by category").assertIsDisplayed()
        compose.onAllNodesWithText("Groceries").onFirst().assertExists()
        compose.onNodeWithText("750 EGP").assertExists()
        compose.onNodeWithText("10%").assertExists()
        compose.onNodeWithContentDescription("Groceries: 750 EGP, 60% of spending").assertExists()
        compose.onNodeWithContentDescription("Spending by category: Groceries 60%, Car 40%").assertExists()
    }

    @Test
    fun `REQ-AND-071 the trend, calendar and weekday charts describe their key numbers for TalkBack`() {
        show(ready())
        scrollTo("Average 1,050 EGP per month")
        compose.onNode(hasContentDescription("Monthly spending: May 900 EGP", substring = true)).assertExists()
        scrollTo("Busiest day: 15 Oct, 850 EGP")
        compose.onNode(hasContentDescription("Busiest day 15 Oct with 850 EGP", substring = true)).assertExists()
        scrollTo("By day of the week")
        compose.onNode(hasContentDescription("Spending by day of the week: Sat 400 EGP", substring = true)).assertExists()
    }

    @Test
    fun `REQ-AND-071 loading shows a skeleton`() {
        show(InsightsState(status = LoadStatus.LOADING))
        compose.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-071 empty period says there is not enough data yet`() {
        show(InsightsState(data = InsightsFixtures.emptySnapshot().toUi(), status = LoadStatus.READY))
        compose.onNodeWithText("Not enough data yet").assertIsDisplayed()
        compose.onNodeWithText("Spending by category").assertDoesNotExist()
    }

    @Test
    fun `REQ-AND-071 error without cached data offers retry`() {
        show(InsightsState(status = LoadStatus.ERROR))
        compose.onNodeWithText("Couldn't compute insights").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `REQ-AND-071 offline without cached data explains and offers retry`() {
        show(InsightsState(status = LoadStatus.OFFLINE))
        compose.onNodeWithText("Connect once to see insights").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-071 sign-in required without cached data`() {
        show(InsightsState(status = LoadStatus.SIGN_IN))
        compose.onNodeWithText("Sign in again").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-071 offline with cached data still renders the charts and says so`() {
        show(ready(LoadStatus.OFFLINE))
        compose.onNodeWithText("1,250 EGP").assertIsDisplayed()
        compose.onNodeWithText("Offline - showing saved data").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-070 picking last month reports the choice and chips are 48dp targets`() {
        show(ready())
        compose.onNode(hasText("Last month") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(InsightsPeriod.PREVIOUS, picked)
        compose.onNode(hasText("This month") and hasClickAction()).assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-072 Arabic mirrors the layout and uses Arabic text and digits`() {
        show(ready())
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("التحليلات").assertIsDisplayed()
        compose.onNodeWithText("المصروف هذا الشهر").assertIsDisplayed()
        compose.onNodeWithText("١٬٢٥٠ ج.م").assertIsDisplayed()
        compose.onNodeWithText("٢٥٪").assertIsDisplayed()
        compose.onNodeWithText("مصروفان يحتاجان مراجعة").assertIsDisplayed()
        scrollTo("المصروف حسب الفئة")
        compose.onAllNodesWithText("بقالة").onFirst().assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `REQ-AND-072 dark mode renders the same content`() {
        show(ready())
        compose.onNodeWithText("1,250 EGP").assertIsDisplayed()
        scrollTo("By day of the week")
        compose.onNodeWithText("By day of the week").assertIsDisplayed()
    }

    @Test
    fun `REQ-AND-072 a 1_5x font scale still shows the total`() {
        RuntimeEnvironment.setFontScale(1.5f)
        show(ready())
        compose.onNodeWithText("1,250 EGP").assertIsDisplayed()
        compose.onNode(hasText("Last month") and hasClickAction()).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `REQ-AND-071 weekday columns are Saturday first and heat levels are quartiles of the busiest day`() {
        assertEquals(0, weekdayIndex(DayOfWeek.SATURDAY))
        assertEquals(1, weekdayIndex(DayOfWeek.SUNDAY))
        assertEquals(6, weekdayIndex(DayOfWeek.FRIDAY))
        assertEquals(5, weekdayIndex(LocalDate.of(2026, 10, 15).dayOfWeek))
        assertEquals(0, heatLevel(0, 100))
        assertEquals(0, heatLevel(5, 0))
        assertEquals(1, heatLevel(1, 100))
        assertEquals(1, heatLevel(25, 100))
        assertEquals(2, heatLevel(26, 100))
        assertEquals(3, heatLevel(75, 100))
        assertEquals(4, heatLevel(76, 100))
        assertEquals(4, heatLevel(100, 100))
    }

    @Test
    fun `REQ-AND-074 home shows the change vs last month, the review banner and the mini trend`() {
        val insight = HomeInsight(2_500, Money(100_000), data.trend, pendingReview = 3)
        compose.setContent {
            MasroofTheme {
                HomeContent(HomeUiState(total = Money(125_000), status = LoadStatus.READY), {}, {}, insight = insight)
            }
        }
        compose.onNodeWithText("1,250 EGP").assertIsDisplayed()
        compose.onNodeWithText("vs last month").assertIsDisplayed()
        compose.onNodeWithText("25%").assertIsDisplayed()
        compose.onNodeWithText("3 expenses need review").assertIsDisplayed()
        compose.onNode(hasContentDescription("Spending over the last 6 months: May 900 EGP", substring = true)).assertExists()
    }

    @Test
    fun `REQ-AND-074 home without analytics yet keeps the plain total card`() {
        compose.setContent {
            MasroofTheme { HomeContent(HomeUiState(total = Money(125_000), status = LoadStatus.READY), {}, {}) }
        }
        compose.onNodeWithText("1,250 EGP").assertIsDisplayed()
        compose.onNodeWithText("vs last month").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AND-074 home mirrors in Arabic`() {
        val insight = HomeInsight(-1_250, Money(100_000), data.trend, pendingReview = 0)
        compose.setContent {
            MasroofTheme {
                HomeContent(HomeUiState(total = Money(125_000), status = LoadStatus.READY), {}, {}, insight = insight)
            }
        }
        compose.onNodeWithText("١٬٢٥٠ ج.م").assertIsDisplayed()
        compose.onNodeWithText("مقابل الشهر الماضي").assertIsDisplayed()
        compose.onNodeWithText("١٢٫٥٪").assertIsDisplayed()
    }
}
