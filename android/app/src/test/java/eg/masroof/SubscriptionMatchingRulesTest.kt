package eg.masroof

import eg.masroof.data.bills.EntryFact
import eg.masroof.data.bills.SubscriptionMatcher
import eg.masroof.data.bills.SubscriptionMatcher.RowFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SubscriptionMatchingRulesTest {
    private val renewal = LocalDate.parse("2026-11-10")

    private fun entry(
        id: String,
        merchant: String,
        amount: Long,
        date: String = "2026-11-10",
    ) = EntryFact(id, SubscriptionMatcher.keyOf(merchant)!!, merchant, amount, "EGP", LocalDate.parse(date))

    private fun matched(
        sub: String,
        amount: Long,
        vararg entries: EntryFact,
    ): String? = SubscriptionMatcher.match(SubscriptionMatcher.keyOf(sub)!!, amount, "EGP", renewal, entries.toList(), emptySet())?.id

    @Test
    fun `REQ-AN-171 a wallet or gift word after the brand makes the entry another merchant`() {
        assertNull(matched("Orange", 30_000, entry("a", "Orange Money", 45_000)))
        assertNull(matched("Orange", 30_000, entry("a", "Orange Money", 30_000)))
        assertNull(matched("Vodafone", 30_000, entry("a", "Vodafone Cash", 30_000)))
        assertNull(matched("Netflix", 19_900, entry("a", "Netflix Gift Card", 19_900)))
        assertNull(matched("Netflix", 19_900, entry("a", "Netflix Pay", 19_900)))
    }

    @Test
    fun `REQ-AN-171 the subscription key is never extended backwards or by a short key`() {
        assertNull(matched("Google One", 5_000, entry("a", "GOOGLE", 5_000)))
        assertNull(matched("YouTube Premium", 9_900, entry("a", "YOUTUBE", 9_900)))
        assertNull(matched("e&", 40_000, entry("a", "E Finance", 40_000)))
        assertNull(matched("Uber One", 5_000, entry("a", "Uber", 5_000)))
        assertEquals("a", matched("e&", 40_000, entry("a", "E&", 40_000)))
    }

    @Test
    fun `REQ-AN-171 the entry may extend the subscription key and the domain spellings still match`() {
        assertEquals("a", matched("Netflix", 19_900, entry("a", "NETFLIX.COM", 19_900)))
        assertEquals("a", matched("Netflix", 19_900, entry("a", "netflix com amsterdam", 19_900)))
        assertEquals("a", matched("Uber", 5_000, entry("a", "Uber One", 5_000)))
    }

    @Test
    fun `REQ-AN-171 the price-change pass needs exactly the same key`() {
        assertEquals("a", matched("Netflix", 19_900, entry("a", "NETFLIX.COM", 22_900)))
        assertNull(matched("Netflix", 19_900, entry("a", "Netflix Amsterdam", 22_900)))
        assertEquals("a", matched("Netflix", 19_900, entry("a", "Netflix Amsterdam", 20_000)))
    }

    @Test
    fun `REQ-AN-171 an exact key beats a prefix key for the same entry whatever the row order`() {
        val e = entry("one", "Uber One", 5_000)
        val rows =
            listOf(
                RowFacts("uber", "uber", 5_000, "EGP", renewal, listOf(e)),
                RowFacts("uber one", "uber one", 5_000, "EGP", renewal, listOf(e)),
            )
        assertEquals("uber one", SubscriptionMatcher.bestMatch(rows, emptySet())?.first)
        assertEquals("uber one", SubscriptionMatcher.bestMatch(rows.reversed(), emptySet())?.first)
    }

    @Test
    fun `REQ-AN-171 one entry does not go to an older row when a newer row is the closer exact match`() {
        val e = entry("e", "Netflix", 20_000, "2026-11-07")
        val older = RowFacts("older", "netflix", 19_900, "EGP", LocalDate.parse("2026-11-05"), listOf(e))
        val newer = RowFacts("newer", "netflix", 20_000, "EGP", LocalDate.parse("2026-11-07"), listOf(e))
        assertEquals("newer", SubscriptionMatcher.bestMatch(listOf(older, newer), emptySet())?.first)
    }

    @Test
    fun `REQ-AN-171 the 10 percent pass runs over every row before any price-change pass`() {
        val exactPrice = entry("fits", "Netflix", 19_900, "2026-11-12")
        val changed = entry("changed", "Netflix", 29_000, "2026-11-10")
        val rowA = RowFacts("a", "netflix", 19_900, "EGP", renewal, listOf(exactPrice, changed))
        val rowB = RowFacts("b", "netflix", 29_000, "EGP", renewal, listOf(exactPrice, changed))
        // the closer "changed" entry would pay row A only through the price-change pass; the 10 percent pass pays B with it first
        val first = SubscriptionMatcher.bestMatch(listOf(rowA, rowB), emptySet())
        assertEquals("b" to "changed", first?.let { it.first to it.second.id })
    }
}
