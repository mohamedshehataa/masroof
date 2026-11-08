package eg.masroof.ask

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AskIntroRoastTest : AskEngineFixture() {
    // region REQ-AN-165

    @Test
    fun `REQ-AN-165 the greeting is the top merchant by total this month and chips are built from the data`() =
        runTest {
            seedOctober()
            val intro = ask().intro()
            val fact = requireNotNull(intro.topMerchant)
            val top = current().merchants.first()
            assertEquals(top.name, fact.name)
            assertEquals(top.total, fact.totalMinor)
            assertEquals("14 visits to ${top.name} this month, 3,210 EGP", en().greetingFact(fact))
            val kinds = intro.chips.map { it.kind }
            assertEquals(
                listOf(
                    AskChipKind.FREQUENCY,
                    AskChipKind.WHY_HIGH,
                    AskChipKind.COMPARE_MONTHS,
                    AskChipKind.WHERE_MONEY_WENT,
                    AskChipKind.PAYDAY,
                    AskChipKind.ROAST,
                ),
                kinds,
            )
            assertEquals("How often do I go to ${top.name}?", en().chipLabel(intro.chips[0]))
            assertEquals("Why is Groceries so high?", en().chipLabel(intro.chips[1]))
        }

    @Test
    fun `REQ-AN-165 every suggestion chip label is itself a question that gets the same answer, in English and Arabic`() =
        runTest {
            seedOctober()
            val intro = ask().intro()
            for (texts in listOf(en(), ar())) {
                for (chip in intro.chips) {
                    val typed = ask().answer(QuestionParser.parse(texts.chipLabel(chip)))
                    val tapped = ask().answer(chip.query)
                    assertEquals("chip ${chip.kind} label '${texts.chipLabel(chip)}'", tapped, typed)
                }
            }
        }

    @Test
    fun `REQ-AN-165 every answer ends with 2 or 3 follow-up chips`() =
        runTest {
            seedOctober()
            val questions =
                listOf(
                    "groceries this month",
                    "where did my money go",
                    "this month vs last month",
                    "how often do I order talabat",
                    "top places",
                    "payday",
                    "biggest expense",
                    "why is groceries so high",
                    "roast me",
                    "blah blah",
                    "how much did I spend on dragons",
                )
            questions.forEach { q ->
                val answer = spend(q)
                if (answer is AskAnswer.UnknownSubject) {
                    assertEquals(3, answer.followUps.size)
                } else if (answer is AskAnswer.NotUnderstood) {
                    assertTrue(answer.followUps.isNotEmpty())
                } else {
                    assertTrue("$q has ${answer.followUps.size} chips", answer.followUps.size in 2..3)
                }
            }
        }

    @Test
    fun `REQ-AN-165 a question that is not understood says so, shows the chips and never guesses a number`() =
        runTest {
            seedOctober()
            for (q in listOf("what is the weather today", "hello", "tell me a joke", "", "؟؟؟")) {
                val answer = spend(q)
                assertTrue("$q -> $answer", answer is AskAnswer.NotUnderstood)
                assertTrue(answer.followUps.isNotEmpty())
            }
            assertEquals("Sorry, I didn’t understand that. Try one of these:", en().text(spend("hello")).headline)
        }

    @Test
    fun `REQ-AN-165 roast me gives 2 or 3 polite lines built only from the user's own figures`() =
        runTest {
            seedOctober()
            // two late-night purchases in Cairo (23:30 and 02:00 local)
            put("n1", 5_000, "2026-10-09T20:30:00Z", category = DINING, merchant = "Talabat")
            put("n2", 5_000, "2026-10-10T23:00:00Z", category = DINING, merchant = "Talabat")
            val answer = spend("roast me") as AskAnswer.Roast
            assertTrue(answer.lines.size in 2..3)
            assertEquals(RoastKind.FREQUENT_MERCHANT, answer.lines[0].kind)
            assertEquals(16, answer.lines[0].count)
            assertEquals(RoastKind.BIGGEST_CATEGORY, answer.lines[1].kind)
            assertTrue(answer.lines.any { it.kind == RoastKind.LATE_NIGHT && it.count >= 2 })
            val text = en().text(answer)
            assertTrue(text.headline.contains("Talabat") || text.headline.contains("TALABAT") || text.headline.contains("16 visits"))
            val arabic = ar().text(spend("اتريق عليا"))
            assertTrue(arabic.headline.isNotBlank())
        }

    @Test
    fun `REQ-AN-165 roast with no data says there is nothing to roast yet`() =
        runTest {
            val answer = spend("roast me") as AskAnswer.Roast
            assertTrue(answer.lines.isEmpty())
            assertEquals("Nothing to roast yet. Add a few entries first.", en().text(answer).headline)
        }

    @Test
    fun `REQ-AN-165 why is X so high compares with the previous period and names the places behind it`() =
        runTest {
            seedOctober()
            put("s1", 100_000, "2026-09-05T10:00:00Z", category = GROCERIES)
            val answer = spend("why is groceries so high") as AskAnswer.Why
            assertEquals(402_000L, answer.totalMinor)
            assertEquals(100_000L, answer.previousMinor)
            assertEquals(30_200, answer.deltaBps)
            assertEquals(listOf("Carrefour", "Spinneys"), answer.merchants.map { it.name }.sorted())
        }

    // endregion

    @Test
    fun `REQ-AN-165 the roast percent rounds half away from zero like the breakdown, not down`() =
        runTest {
            seedCategories()
            put("a", 20_000, "2026-10-05T10:00:00Z", category = GROCERIES)
            put("b", 10_000, "2026-10-06T10:00:00Z", category = DINING)
            val answer = spend("roast me") as AskAnswer.Roast
            // 20,000 of 30,000 is 6,667 bps = 66.67 percent: 67, never 66
            assertEquals(67, answer.lines.first { it.kind == RoastKind.BIGGEST_CATEGORY }.percent)
        }

    @Test
    fun `REQ-AN-165 counts use plural forms in English and Arabic and Other is a resource`() {
        assertEquals("1 visit", en().visits(1))
        assertEquals("2 visits", en().visits(2))
        assertEquals("زيارة واحدة", ar().visits(1))
        assertEquals("زيارتان", ar().visits(2))
        assertEquals("٣ زيارات", ar().visits(3))
        assertEquals("١١ زيارة", ar().visits(11))
        val share = CategoryShare(null, LocalizedText("", ""), 100, 10_000, 100, null, isOther = true)
        assertEquals("Other", en().shareName(share))
        assertEquals("أخرى", ar().shareName(share))
        val period = PeriodInfo(AskPeriod.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

        fun spent(others: Int) = AskAnswer.SpendTotal(period, null, 100, 2, others, emptyList(), emptyList())
        assertTrue(ar().text(spent(2)).lines.contains("عمليتان بعملة تانية مش محسوبتين"))
        assertTrue(ar().text(spent(5)).lines.contains("٥ عمليات بعملات تانية مش محسوبة"))
        assertTrue(ar().text(spent(2)).lines.contains("عمليتان"))
        assertTrue(en().text(spent(3)).lines.contains("3 entries in other currencies not included"))
    }
}
