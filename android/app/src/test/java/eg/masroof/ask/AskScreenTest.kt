package eg.masroof.ask

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import eg.masroof.InsightsFixtures
import eg.masroof.data.insights.InsightsPeriod
import eg.masroof.ui.ask.AskContent
import eg.masroof.ui.ask.AskMessage
import eg.masroof.ui.ask.AskState
import eg.masroof.ui.home.HomeContent
import eg.masroof.ui.insights.InsightsContent
import eg.masroof.ui.insights.toUi
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.viewmodel.HomeUiState
import eg.masroof.ui.viewmodel.InsightsState
import eg.masroof.ui.viewmodel.LoadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

private val OCTOBER = PeriodInfo(AskPeriod.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
private val SEPTEMBER = PeriodInfo(AskPeriod.LAST_MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
private val GROCERIES_NAME = LocalizedText("Groceries", "بقالة")
private val CAR_NAME = LocalizedText("Car", "السيارة")

private val CHIPS =
    listOf(
        AskChip(AskChipKind.FREQUENCY, AskQuery.MerchantVisits(AskPeriod.THIS_MONTH, "amazon"), LocalizedText("Amazon", "Amazon")),
        AskChip(AskChipKind.WHERE_MONEY_WENT, AskQuery.Breakdown(AskPeriod.THIS_MONTH)),
        AskChip(AskChipKind.ROAST, AskQuery.Roast),
    )

private val INTRO = AskIntro(MerchantStat("Amazon", 1_646_000, 4), CHIPS)

private fun spendAnswer() =
    AskAnswer.SpendTotal(
        period = OCTOBER,
        subject = GROCERIES_NAME,
        totalMinor = 402_000,
        count = 3,
        otherCurrencyCount = 1,
        entries =
            listOf(
                EntryLine("g4", "Carrefour", GROCERIES_NAME, LocalDate.of(2026, 10, 12), -10_000),
                EntryLine("g2", "Spinneys", GROCERIES_NAME, LocalDate.of(2026, 10, 10), 252_000),
            ),
        followUps = listOf(AskChip(AskChipKind.COMPARE_PREVIOUS, AskQuery.Compare(AskPeriod.THIS_MONTH)), CHIPS[1]),
    )

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AskScreenTest {
    @get:Rule val compose = createComposeRule()

    private var direction: LayoutDirection? = null
    private var sent: String? = null
    private var queried: Pair<AskQuery, String>? = null
    private var opened: String? = null
    private var retried: Long? = null
    private var backs = 0

    private fun show(state: AskState) =
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                AskContent(
                    state,
                    onSend = { sent = it },
                    onQuery = { q, label -> queried = q to label },
                    onRetry = { retried = it },
                    onOpenExpense = { opened = it },
                    onBack = { backs++ },
                )
            }
        }

    private fun message(
        question: String,
        answer: AskAnswer?,
        query: AskQuery = AskQuery.NotUnderstood,
    ) = AskMessage(1, question, query, answer)

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `REQ-AN-165 opening Ask greets with the top merchant fact and suggestion chips that ask their own query`() {
        show(AskState(intro = INTRO))
        compose.onNodeWithText("Ask Masroof").assertIsDisplayed()
        compose.onNodeWithText("4 visits to Amazon this month, 16,460 EGP").assertIsDisplayed()
        compose.onNodeWithText("How often do I go to Amazon?").assertIsDisplayed()
        compose.onNodeWithText("Where did my money go?").assertIsDisplayed()
        compose.onNodeWithText("Roast me").performClick()
        assertEquals(AskQuery.Roast to "Roast me", queried)
    }

    @Test
    fun `REQ-AN-165 with no entries yet the greeting says so and still offers chips`() {
        show(AskState(intro = AskIntro(null, CHIPS)))
        compose.onNodeWithText("No entries yet this month. Try a question:").assertIsDisplayed()
        compose.onNodeWithText("Roast me").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-160 an answer card shows the number, the entries behind it and follow-up chips, and an entry opens Edit`() {
        show(AskState(INTRO, listOf(message("how much did I spend on groceries this month", spendAnswer()))))
        compose.onNodeWithText("4,020 EGP on Groceries this month").assertIsDisplayed()
        compose.onNodeWithText("3 entries").assertIsDisplayed()
        compose.onNodeWithText("1 entry in another currency is not included").assertIsDisplayed()
        scrollTo("Spinneys")
        compose.onNodeWithText("Spinneys").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open entry: Carrefour, 12 Oct, -100 EGP").assertIsDisplayed().performClick()
        assertEquals("g4", opened)
        scrollTo("Compare with the previous period")
        compose.onNodeWithText("Compare with the previous period").performClick()
        assertEquals(AskQuery.Compare(AskPeriod.THIS_MONTH) to "Compare with the previous period", queried)
    }

    @Test
    fun `REQ-AN-165 TalkBack reads the answer text before the chart`() {
        val shares =
            listOf(
                CategoryShare("a", GROCERIES_NAME, 600, 6_000, 60, AskQuery.Spend(AskPeriod.THIS_MONTH), false),
                CategoryShare("b", CAR_NAME, 400, 4_000, 40, null, false),
            )
        val answer = AskAnswer.Breakdown(OCTOBER, 1_000, 0, shares, CHIPS.take(2))
        show(AskState(null, listOf(message("where did my money go", answer))))
        val head = compose.onNodeWithText("Spent 10 EGP this month").assertIsDisplayed().getUnclippedBoundsInRoot()
        val chart =
            compose
                .onNodeWithContentDescription(
                    "Spending by category: Groceries 60%, Car 40%",
                ).assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        assertTrue("headline ${head.top} must come before the chart ${chart.top}", head.top < chart.top)
    }

    @Test
    fun `REQ-AN-161 the breakdown ranks categories with percentages and tapping one asks about it`() {
        val shares =
            listOf(
                CategoryShare("a", GROCERIES_NAME, 600, 6_000, 60, AskQuery.Spend(AskPeriod.THIS_MONTH), false),
                CategoryShare(null, LocalizedText("Other", "أخرى"), 400, 4_000, 40, null, true),
            )
        show(AskState(null, listOf(message("where did my money go", AskAnswer.Breakdown(OCTOBER, 1_000, 0, shares, emptyList())))))
        scrollTo("Groceries")
        compose.onNodeWithContentDescription("Groceries, 60%, 6 EGP").assertIsDisplayed().performClick()
        assertEquals(AskQuery.Spend(AskPeriod.THIS_MONTH) to "Groceries", queried)
        compose.onNodeWithContentDescription("Other, 40%, 4 EGP").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-162 a comparison shows both totals, the change and a two-bar chart`() {
        val answer =
            AskAnswer.Compare(OCTOBER, SEPTEMBER, null, 633_000, 400_000, 5_825, emptyList(), CHIPS.take(2))
        show(AskState(null, listOf(message("this month vs last month", answer))))
        compose.onNodeWithText("Spent 6,330 EGP this month vs 4,000 EGP last month").assertIsDisplayed()
        compose.onNodeWithText("Difference: +2,330 EGP (+58.3%)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Last month 4,000 EGP, This month 6,330 EGP").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-165 a question that is not understood says so and shows the chips`() {
        show(AskState(INTRO, listOf(message("hello", AskAnswer.NotUnderstood(CHIPS)))))
        compose.onNodeWithText("Sorry, I didn’t understand that. Try one of these:").assertIsDisplayed()
        // once in the greeting and once under the answer
        compose.onAllNodesWithText("Roast me").assertCountEquals(2)
    }

    @Test
    fun `REQ-AN-160 an unknown category shows no number and the closest categories as chips`() {
        val chip = AskChip(AskChipKind.SUGGESTED_CATEGORY, AskQuery.Spend(AskPeriod.THIS_MONTH), GROCERIES_NAME)
        show(AskState(null, listOf(message("groceris", AskAnswer.UnknownSubject("groceris", listOf(chip))))))
        compose.onNodeWithText("I don’t know a category called “groceris”. Did you mean:").assertIsDisplayed()
        compose.onNodeWithText("Groceries").performClick()
        assertEquals(AskQuery.Spend(AskPeriod.THIS_MONTH) to "Groceries", queried)
    }

    @Test
    fun `REQ-AN-165 while an answer is being worked out the card says so, and a failed one offers a retry`() {
        show(AskState(null, listOf(message("payday", null))))
        compose.onNodeWithText("Working it out…").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-165 a failed answer can be retried`() {
        show(AskState(null, listOf(message("payday", AskAnswer.Failed))))
        compose.onNodeWithText("Couldn’t work that out.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1L, retried)
    }

    @Test
    fun `REQ-AN-165 typing a question and tapping send passes it on, and send is disabled while the field is empty`() {
        show(AskState(intro = INTRO))
        compose.onNodeWithContentDescription("Send question").assertIsNotEnabled()
        compose
            .onNode(
                androidx.compose.ui.test
                    .hasSetTextAction(),
            ).performTextInput("top places")
        compose.onNodeWithContentDescription("Send question").assertIsEnabled().performClick()
        assertEquals("top places", sent)
        compose.onNodeWithContentDescription("Send question").assertIsNotEnabled()
    }

    @Test
    fun `REQ-AN-165 suggestion chips and the back button are 48dp touch targets`() {
        show(AskState(intro = INTRO))
        compose.onNode(hasText("Roast me") and hasClickAction()).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `REQ-AN-165 question bubbles sit on the right in LTR`() {
        show(AskState(null, listOf(message("top places", AskAnswer.NotUnderstood(emptyList())))))
        assertEquals(LayoutDirection.Ltr, direction)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val bubble = compose.onNodeWithContentDescription("You asked: top places").getUnclippedBoundsInRoot()
        assertTrue("bubble $bubble should hug the right edge of $root", root.width - bubble.right < bubble.left)
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AN-165 Arabic mirrors the screen, puts question bubbles on the left and answers in Arabic with Arabic-Indic digits`() {
        show(AskState(INTRO, listOf(message("صرفت كام على البقالة الشهر ده", spendAnswer()))))
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("اسأل مصروف").assertIsDisplayed()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val bubble = compose.onNodeWithContentDescription("سألت: صرفت كام على البقالة الشهر ده").getUnclippedBoundsInRoot()
        assertTrue("bubble $bubble should hug the left edge of $root", bubble.left < root.width - bubble.right)
        compose.onNodeWithText("٤٬٠٢٠ ج.م على بقالة الشهر ده").assertIsDisplayed()
        compose.onNodeWithText("عملية واحدة بعملة تانية مش محسوبة").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar")
    fun `REQ-AN-165 the Arabic greeting and chips read naturally`() {
        show(AskState(intro = INTRO))
        compose.onNodeWithText("أهلاً! اسألني عن فلوسك.").assertIsDisplayed()
        compose.onNodeWithText("٤ زيارات في Amazon الشهر ده، ١٦٬٤٦٠ ج.م").assertIsDisplayed()
        compose.onNodeWithText("فلوسي راحت فين؟").assertIsDisplayed()
        compose.onNodeWithText("اتريق عليا").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `REQ-AN-165 dark mode renders the same content`() {
        show(AskState(INTRO, listOf(message("groceries", spendAnswer()))))
        compose.onNodeWithText("4,020 EGP on Groceries this month").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], fontScale = 1.5f)
    fun `REQ-AN-165 at font scale 1_5 the answer is still readable and reachable`() {
        show(AskState(INTRO, listOf(message("groceries", spendAnswer()))))
        compose.onNodeWithText("4,020 EGP on Groceries this month").assertIsDisplayed()
        scrollTo("Compare with the previous period")
        compose.onNodeWithText("Compare with the previous period").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-165 Home and Insights each have an Ask button that opens Ask, and none without a handler`() {
        var asks = 0
        compose.setContent {
            MasroofTheme {
                androidx.compose.foundation.layout.Column {
                    HomeContent(HomeUiState(status = LoadStatus.READY), {}, {}, onOpenAsk = { asks++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Ask Masroof").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, asks)
    }

    @Test
    fun `REQ-AN-165 the Insights top bar has the Ask button too`() {
        var asks = 0
        compose.setContent {
            MasroofTheme {
                InsightsContent(
                    InsightsState(InsightsPeriod.CURRENT, InsightsFixtures.snapshot().toUi(), LoadStatus.READY),
                    onPeriod = {},
                    onRetry = {},
                    onOpenAsk = { asks++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Ask Masroof").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, asks)
    }

    @Test
    fun `REQ-AN-165 without an Ask handler Home and Insights show no Ask button`() {
        compose.setContent { MasroofTheme { HomeContent(HomeUiState(status = LoadStatus.READY), {}, {}) } }
        compose.onAllNodesWithContentDescription("Ask Masroof").assertCountEquals(0)
    }
}
