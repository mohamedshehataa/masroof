package eg.masroof

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eg.masroof.core.CairoTime
import eg.masroof.data.db.AssetEntity
import eg.masroof.data.db.AssetLotEntity
import eg.masroof.data.db.MetalPriceEntity
import eg.masroof.data.goals.GoalBacking
import eg.masroof.data.wealth.HoldingDetail
import eg.masroof.data.wealth.LotDraft
import eg.masroof.data.wealth.MetalMath
import eg.masroof.data.wealth.MetalOutcome
import eg.masroof.ui.goals.GoalDetailActions
import eg.masroof.ui.goals.GoalDetailContent
import eg.masroof.ui.goals.GoalDetailState
import eg.masroof.ui.theme.MasroofTheme
import eg.masroof.ui.wealth.MetalActions
import eg.masroof.ui.wealth.MetalDetailContent
import eg.masroof.ui.wealth.MetalUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MetalScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 8)

    private fun at(
        m: Int,
        d: Int,
    ) = CairoTime.noonOf(LocalDate.of(2026, m, d)).toEpochMilli()

    private val asset =
        AssetEntity("a1", "GOLD", "Wedding set", "EGP", null, 21, null, null, null, null, null, createdAtMillis = 1, updatedAtMillis = 1)
    private val lots =
        listOf(
            AssetLotEntity("l3", "a1", "SELL", 5_000, 2_400_000, "EGP", at(7, 1), null, null),
            AssetLotEntity("l2", "a1", "BUY", 10_000, 4_500_000, "EGP", at(6, 1), null, null),
            AssetLotEntity("l1", "a1", "BUY", 30_000, 12_000_000, "EGP", at(5, 9), null, null),
        )

    private fun holding(prices: List<MetalPriceEntity>) =
        HoldingDetail(asset, lots, MetalMath.valuationOf(asset, lots, prices, today, CairoTime::dateOf), null, today)

    private fun show(
        state: MetalUiState,
        actions: MetalActions = MetalActions(),
    ) = compose.setContent { MasroofTheme { MetalDetailContent(state, actions) } }

    @Test
    fun `REQ-AN-173 the detail shows grams, value, average, gain and realised gain from the typed price`() {
        val price = MetalPriceEntity("p1", "GOLD", 21, 480_000, "EGP", at(11, 8))
        show(MetalUiState(loaded = true, holding = holding(listOf(price))))
        compose.onNodeWithText("35 g").assertIsDisplayed()
        compose.onNodeWithText("168,000", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Average paid: 4,125", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Gain: +23,625", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+16.36%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Gain on sales: 3,375", substring = true).assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-173 without a price the screen says Add today's price and values the gold at cost`() {
        show(MetalUiState(loaded = true, holding = holding(emptyList())))
        compose.onNodeWithText("Add today's price").assertIsDisplayed()
        compose.onNodeWithText("Worth (at cost, no price yet)").assertIsDisplayed()
    }

    @Test
    fun `REQ-AN-173 a price older than 7 days asks for an update`() {
        val old = MetalPriceEntity("p1", "GOLD", 21, 480_000, "EGP", at(10, 30))
        show(MetalUiState(loaded = true, holding = holding(listOf(old))))
        compose.onNodeWithText("Price from", substring = true).assertIsDisplayed()
    }

    @Test
    fun `REQ-EXP-063 selling more than held shows You hold only X g`() {
        var saved: LotDraft? = null
        val actions =
            MetalActions(onSaveLot = { _, draft, done ->
                saved = draft
                done(MetalOutcome.OnlyHold(35_000L))
            })
        show(MetalUiState(loaded = true, holding = holding(emptyList())), actions)
        compose.onNodeWithText("Record a sale").performScrollTo().performClick()
        compose.onNodeWithText("Grams").performTextInput("40")
        compose.onNodeWithText("Received (EGP)").performTextInput("1000")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("You hold only 35 g").assertIsDisplayed()
        assertEquals(40_000L, saved?.quantityMilli)
        assertEquals("SELL", saved?.side)
    }

    @Test
    fun `REQ-EXP-063 empty grams is a field error and nothing is saved`() {
        var calls = 0
        show(MetalUiState(loaded = true, holding = holding(emptyList())), MetalActions(onSaveLot = { _, _, _ -> calls++ }))
        compose.onNodeWithText("Buy more").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter the grams, more than 0").assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test
    fun `REQ-AN-174 a goal backed by gold shows including gold worth X`() {
        val goal =
            eg.masroof.data.db
                .SavingsGoalEntity("g1", "Sahel chalet", "🏖️", 50_000_000L, "EGP", null, 0, 1L, null, null)
        val card =
            eg.masroof.ui.goals.GoalCardUi(
                goal.id,
                goal.name,
                goal.emoji,
                "EGP",
                false,
                eg.masroof.data.goals.GoalMath.progress(
                    eg.masroof.data.goals.GoalMath.Input(
                        50_000_000L,
                        1_000_000L,
                        today,
                        null,
                        today,
                        1,
                        extraSavedMinor = 16_800_000L,
                    ),
                ),
            )
        val state = GoalDetailState(loading = false, card = card, backing = listOf(GoalBacking("a1", "Wedding set", "GOLD", 16_800_000L)))
        compose.setContent {
            MasroofTheme {
                GoalDetailContent(state, GoalDetailActions({}, {}, {}, {}, {}), null, false, { _, _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithText("including gold worth", substring = true).assertIsDisplayed()
        compose.onNodeWithText("168,000", substring = true).assertIsDisplayed()
        assertNotNull(card)
    }
}
