package eg.masroof

import eg.masroof.data.answers.AnswerCard
import eg.masroof.data.answers.AnswerCards
import eg.masroof.data.answers.AnswerState
import eg.masroof.data.answers.AtmChoice
import eg.masroof.data.answers.EntryLink
import eg.masroof.data.answers.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOW = ANSWER_NOW.toEpochMilli()
private val CIB = answerAccount("cib", "CIB", last4 = "1111")
private val NBE = answerAccount("nbe", "NBE", last4 = "2222")

private fun cards(
    entries: List<eg.masroof.data.db.ExpenseEntity>,
    accounts: List<eg.masroof.data.db.AccountEntity> = listOf(CIB, NBE),
    state: AnswerState = AnswerState(),
    includeCash: Boolean = false,
) = AnswerCards.generate(entries, accounts, state, includeCash, NOW)

/** STORY-208: card generation is a pure function over recent entries and accounts. */
class AnswerCardsTest {
    // ---- REQ-EXP-064

    @Test
    fun `REQ-EXP-064 a refund lists purchases of the same merchant key, amount at least the refund, newest first, at most 3`() {
        val refund = answerEntry("r", type = "REFUND", merchant = "Amazon", amount = 45_000, at = ago(1), last4 = "1111")
        val purchases =
            listOf(
                answerEntry("p5", merchant = "AMAZON ", amount = 120_000, at = ago(5), source = "MANUAL"),
                answerEntry("p10", merchant = "amazon", amount = 45_000, at = ago(10)),
                answerEntry("small", merchant = "Amazon", amount = 40_000, at = ago(20)),
                answerEntry("p30", merchant = "Amazon", amount = 90_000, at = ago(30)),
                answerEntry("p40", merchant = "Amazon", amount = 200_000, at = ago(40)),
                answerEntry("other", merchant = "Noon", amount = 120_000, at = ago(2)),
            )
        val card = cards(listOf(refund) + purchases).filterIsInstance<AnswerCard.Refund>().single()
        assertEquals(listOf("p5", "p10", "p30"), card.candidates.map { it.id })
    }

    @Test
    fun `REQ-EXP-064 a purchase older than 60 days before the refund is not a candidate`() {
        val refund = answerEntry("r", type = "REFUND", merchant = "Amazon", amount = 100, at = ago(1))
        val old = answerEntry("old", merchant = "Amazon", amount = 1_000, at = ago(1) - 61L * 24 * 60 * 60 * 1000)
        val edge = answerEntry("edge", merchant = "Amazon", amount = 1_000, at = ago(1) - 59L * 24 * 60 * 60 * 1000)
        val card = cards(listOf(refund, old, edge)).filterIsInstance<AnswerCard.Refund>().single()
        assertEquals(listOf("edge"), card.candidates.map { it.id })
    }

    @Test
    fun `REQ-EXP-064 a received INCOME from a merchant with a purchase in the last 60 days asks, one without does not`() {
        val income = answerEntry("in", type = "INCOME", merchant = "Amazon", amount = 45_000, at = ago(1))
        val purchase = answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(6))
        assertEquals(listOf("in"), cards(listOf(income, purchase)).filterIsInstance<AnswerCard.Refund>().map { it.entryId })
        assertTrue(cards(listOf(income)).isEmpty())
        val stranger = answerEntry("in2", type = "INCOME", merchant = "Employer", amount = 45_000, at = ago(1))
        assertTrue(cards(listOf(stranger, purchase)).isEmpty())
    }

    @Test
    fun `REQ-EXP-064 a captured REFUND with no matching purchase still asks, so Pick another stays possible`() {
        val refund = answerEntry("r", type = "REFUND", merchant = "Amazon", amount = 45_000)
        val card = cards(listOf(refund)).single() as AnswerCard.Refund
        assertTrue(card.candidates.isEmpty())
    }

    @Test
    fun `REQ-EXP-064 an answered or linked refund never returns, and a manual one never asks`() {
        val refund = answerEntry("r", type = "REFUND", merchant = "Amazon", amount = 45_000)
        val purchase = answerEntry("p", merchant = "Amazon", amount = 120_000, at = ago(3))
        assertEquals(1, cards(listOf(refund, purchase)).size)
        assertTrue(cards(listOf(refund, purchase), state = AnswerState(answered = setOf("r"))).isEmpty())
        val linked = AnswerState(links = setOf(EntryLink(LinkKind.REFUND, "r", "p")))
        assertTrue(cards(listOf(refund, purchase), state = linked).isEmpty())
        assertTrue(cards(listOf(refund.copy(source = "MANUAL"), purchase)).isEmpty())
    }

    // ---- REQ-EXP-065

    @Test
    fun `REQ-EXP-065 an outgoing captured transfer from CIB offers the other accounts`() {
        val out = answerEntry("t", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER")
        val card = cards(listOf(out)).single() as AnswerCard.Transfer
        assertTrue(card.outgoing)
        assertEquals(listOf("nbe"), card.options.map { it.id })
    }

    @Test
    fun `REQ-EXP-065 an incoming captured transfer asks the mirror question`() {
        val inn = answerEntry("t", type = "INCOME", amount = 500_000, last4 = "2222", method = "INSTAPAY", merchant = "Ahmed")
        val card = cards(listOf(inn)).single() as AnswerCard.Transfer
        assertEquals(false, card.outgoing)
        assertEquals(listOf("cib"), card.options.map { it.id })
    }

    @Test
    fun `REQ-EXP-065 an unrelated income, a gam3eya transfer and a lone account ask nothing`() {
        val salary = answerEntry("s", type = "INCOME", amount = 500_000, last4 = "2222")
        val gam3eya = answerEntry("g", type = "TRANSFER", amount = 100_000, last4 = "1111", method = "GAM3EYA_PAYMENT")
        assertTrue(cards(listOf(salary, gam3eya)).isEmpty())
        val out = answerEntry("t", type = "TRANSFER", amount = 500_000, last4 = "1111", method = "BANK_TRANSFER")
        assertTrue(cards(listOf(out), accounts = listOf(CIB)).isEmpty())
    }

    @Test
    fun `REQ-EXP-065 a transfer from an unknown card asks nothing (both sides must be known), and a linked pair never asks`() {
        val out = answerEntry("t", type = "TRANSFER", amount = 500_000, last4 = "9999", method = "BANK_TRANSFER")
        assertTrue(cards(listOf(out)).filterIsInstance<AnswerCard.Transfer>().isEmpty())
        val state = AnswerState(links = setOf(EntryLink(LinkKind.TRANSFER, "t", "x")))
        val known = out.copy(instrumentLast4 = "1111")
        assertTrue(cards(listOf(known), state = state).filterIsInstance<AnswerCard.Transfer>().isEmpty())
    }

    // ---- REQ-EXP-066

    @Test
    fun `REQ-EXP-066 a last four that matches no account asks once per card, with the newest capture as the excerpt`() {
        val old = answerEntry("a", merchant = "Old shop", amount = 100, at = ago(5), last4 = "1898")
        val new = answerEntry("b", merchant = "Talabat", amount = 25_000, at = ago(1), last4 = "1898")
        val known = answerEntry("c", merchant = "Shop", last4 = "1111")
        val card = cards(listOf(old, new, known)).single() as AnswerCard.UnknownCard
        assertEquals("1898", card.last4)
        assertEquals("Talabat", card.merchant)
        assertEquals(25_000L, card.amountMinor)
    }

    @Test
    fun `REQ-EXP-066 an ignored, linked or known card asks nothing`() {
        val e = answerEntry("a", last4 = "1898")
        assertEquals(1, cards(listOf(e)).size)
        assertTrue(cards(listOf(e), state = AnswerState(ignoredCards = setOf("1898"))).isEmpty())
        assertTrue(cards(listOf(e), state = AnswerState(aliases = mapOf("1898" to "cib"))).isEmpty())
        assertTrue(cards(listOf(e), accounts = listOf(answerAccount("x", "Other", last4 = "1898"))).isEmpty())
    }

    // ---- REQ-EXP-067

    @Test
    fun `REQ-EXP-067 an ATM withdrawal with no Cash account and no choice asks, once, for the newest`() {
        val a = answerEntry("a", type = "CASH_WITHDRAWAL", amount = 200_000, at = ago(1), last4 = "1111")
        val b = answerEntry("b", type = "CASH_WITHDRAWAL", amount = 100_000, at = ago(4), last4 = "1111")
        val card = cards(listOf(a, b)).single() as AnswerCard.Atm
        assertEquals("a", card.entryId)
    }

    @Test
    fun `REQ-EXP-067 no card once the choice is made, the setting is on, or a Cash account exists`() {
        val a = answerEntry("a", type = "CASH_WITHDRAWAL", amount = 200_000, last4 = "1111")
        assertTrue(cards(listOf(a), state = AnswerState(atmChoice = AtmChoice.TRACK)).isEmpty())
        assertTrue(cards(listOf(a), state = AnswerState(atmChoice = AtmChoice.COUNT)).isEmpty())
        assertTrue(cards(listOf(a), includeCash = true).isEmpty())
        assertTrue(cards(listOf(a), accounts = listOf(CIB, answerAccount("cash", "Cash", kind = "CASH"))).isEmpty())
        assertTrue(cards(listOf(a), state = AnswerState(answered = setOf("a"))).isEmpty())
    }

    // ---- all cards

    @Test
    fun `REQ-EXP-064 only the last 30 days ask, newest first, and no more than ten cards`() {
        val stale = answerEntry("stale", type = "TRANSFER", amount = 100, last4 = "1111", method = "BANK_TRANSFER", at = ago(31))
        assertTrue(cards(listOf(stale)).isEmpty())
        val many = (1..14).map { answerEntry("t$it", type = "CASH_WITHDRAWAL", at = ago(1)).copy(instrumentLast4 = "40%02d".format(it)) }
        val result = cards(many)
        assertEquals(AnswerCards.MAX_CARDS, result.size)
        assertEquals(result.map { it.occurredAtMillis }.sortedDescending(), result.map { it.occurredAtMillis })
    }

    @Test
    fun `REQ-EXP-066 a card carries the structured record and the last four only`() {
        val e = answerEntry("a", merchant = "Talabat", amount = 25_000, last4 = "1898")
        val card = cards(listOf(e)).single()
        assertEquals("card-1898", card.key)
        assertEquals("1898", (card as AnswerCard.UnknownCard).last4)
    }
}
