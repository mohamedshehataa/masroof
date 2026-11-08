package eg.masroof

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eg.masroof.data.answers.AccountOption
import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.answers.PurchaseOption
import eg.masroof.ui.answers.AnswerActions
import eg.masroof.ui.answers.AnswerCardsRow
import eg.masroof.ui.settings.AnswersSettingsContent
import eg.masroof.ui.theme.MasroofTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val AT = ago(1)

private val refundCard =
    AnswerCard.Refund(
        "r",
        "Amazon",
        45_000,
        "EGP",
        AT,
        listOf(PurchaseOption("p", "Amazon", 120_000, "EGP", ago(5))),
    )
private val atmCard = AnswerCard.Atm("w", "CIB ATM", 200_000, "EGP", AT)
private val cardCard = AnswerCard.UnknownCard("1898", "Talabat", 25_000, "EGP", AT)
private val transferCard =
    AnswerCard.Transfer("t", "CIB", 500_000, "EGP", AT, true, listOf(AccountOption("nbe", "NBE"), AccountOption("x", "Savings")))

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AnswerCardsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-EXP-064 the refund card asks about the one candidate and shows merchant, amount and date only`() {
        val linked = mutableListOf<String>()
        compose.setContent {
            MasroofTheme { AnswerCardsRow(listOf(refundCard), AnswerActions(onLinkRefund = { r, p -> linked += "$r>$p" })) }
        }
        compose.onNodeWithText("Needs an answer").assertIsDisplayed()
        compose.onNodeWithText("Amazon · 450 EGP", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Is this a refund for Amazon", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not a refund").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Pick another").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Yes, link it").performClick()
        assertEquals(listOf("r>p"), linked)
    }

    @Test
    fun `REQ-EXP-066 the unknown card shows the last four and offers Add, Link and Ignore`() {
        val ignored = mutableListOf<String>()
        compose.setContent { MasroofTheme { AnswerCardsRow(listOf(cardCard), AnswerActions(onIgnoreCard = { ignored += it })) } }
        compose.onNodeWithText("•••• 1898 isn't in Masroof").assertIsDisplayed()
        compose.onNodeWithText("Add account").assertIsDisplayed()
        compose.onNodeWithText("Link to an account").assertIsDisplayed()
        compose.onNodeWithText("Ignore this card").performClick()
        assertEquals(listOf("1898"), ignored)
    }

    @Test
    fun `REQ-EXP-067 the ATM card offers Track it and Count as spending`() {
        val answers = mutableListOf<String>()
        compose.setContent {
            MasroofTheme {
                AnswerCardsRow(listOf(atmCard), AnswerActions(onTrack = { answers += "track" }, onCount = { answers += "count" }))
            }
        }
        compose.onNodeWithText("Keep track of this cash?").assertIsDisplayed()
        compose.onNodeWithText("Track it").performClick()
        compose.onNodeWithText("Count as spending").performClick()
        assertEquals(listOf("track", "count"), answers)
    }

    @Test
    fun `REQ-EXP-065 the transfer card asks which account when there are several and font scale 1_5 does not hide the answers`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MasroofTheme { AnswerCardsRow(listOf(transferCard), AnswerActions(onTransferChoose = { chosen += it.entryId })) }
            }
        }
        compose.onNodeWithText("Did this go to one of your accounts?").assertIsDisplayed()
        compose.onNodeWithText("To someone else").assertIsDisplayed()
        compose.onNodeWithText("Choose the account").performClick()
        assertEquals(listOf("t"), chosen)
    }

    @Test
    fun `REQ-EXP-066 Settings lists ignored cards and restores one, and changes the ATM choice`() {
        val restored = mutableListOf<String>()
        val atm = mutableListOf<AtmChoice?>()
        compose.setContent {
            MasroofTheme { AnswersSettingsContent(AtmChoice.TRACK, listOf("1898"), { atm += it }, { restored += it }) }
        }
        compose.onNodeWithText("•••• 1898").assertIsDisplayed()
        compose.onNodeWithText("Restore").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Count as spending").performClick()
        assertEquals(listOf("1898"), restored)
        assertEquals(listOf<AtmChoice?>(AtmChoice.COUNT), atm)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar")
class AnswerCardsScreenArabicTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `REQ-EXP-066 Arabic mirrors the row and shows Arabic-Indic digits`() {
        var direction: LayoutDirection? = null
        compose.setContent {
            MasroofTheme {
                direction = LocalLayoutDirection.current
                AnswerCardsRow(listOf(cardCard, atmCard), AnswerActions())
            }
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithText("محتاجة ردّك").assertIsDisplayed()
        compose.onNodeWithText("•••• 1898 مش موجود في مصروف").assertIsDisplayed()
        compose.onNodeWithText("تجاهل الكارت ده").assertIsDisplayed()
    }
}
