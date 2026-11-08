package eg.masroof.ask

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug class: a 4-digit number in a question. It is a year only with a cue before it (in / for / since / year / سنة ...),
 * next to a month name or day, or as one side of an explicit "2025 vs 2024"; every other number is an amount that is ignored.
 * A year that is read must lie in [2026 - 30, 2026]. The result is therefore always the year period(s), the default period
 * with the amount ignored, or PeriodUnreadable - never a silent this-month answer for a year that was meant as one.
 */
class YearQuestionsTest {
    private val now = 2026
    private val years = listOf(1990, 1995, 2000, 2005, 2010, 2020, 2024, 2025, 2026, 2027, 2030, 2099)
    private val groceries = AskSubject.Text("groceries")
    private val tm = AskPeriod.THIS_MONTH
    private val unreadable = AskQuery.PeriodUnreadable

    private fun readable(y: Int) = y in (now - 30)..now

    private fun year(
        y: Int,
        query: (AskPeriod) -> AskQuery,
    ): AskQuery = if (readable(y)) query(AskPeriod.year(y)) else unreadable

    @Test
    fun `REQ-AN-160 generated year questions follow the cue rules and are never a silent this-month answer for a year`() {
        var combinations = 0
        val failures = ArrayList<String>()

        fun expect(
            question: String,
            expected: AskQuery,
        ) {
            combinations++
            val actual = QuestionParser.parse(question, now)
            if (actual != expected) failures.add("[$question] expected $expected but was $actual")
        }
        val amountDefault = AskQuery.Spend(tm, groceries)
        val amountImplicit = AskQuery.Spend(tm, groceries, implicit = true)
        for (y in years) {
            // no cue: an amount
            expect("groceries $y", amountImplicit)
            expect("$y groceries", amountImplicit)
            expect("spent $y", AskQuery.Spend(tm))
            expect("spent $y on groceries", amountDefault)
            expect("صرفت $y على البقالة الشهر ده", AskQuery.Spend(tm, AskSubject.Text("البقاله")))
            expect("is $y too much for groceries", amountDefault)
            expect("more than $y on groceries this month", amountDefault)
            expect("less than $y on groceries this month", amountDefault)
            expect("$y egp on groceries", amountImplicit)
            expect("$y جنيه على البقالة الشهر ده", AskQuery.Spend(tm, AskSubject.Text("البقاله")))
            // cue: a year, or unreadable
            expect("groceries in $y", year(y) { AskQuery.Spend(it, groceries) })
            expect("groceries since $y", year(y) { AskQuery.Spend(AskPeriod.sinceYear(y), groceries) })
            expect("صرفت كام من $y", year(y) { AskQuery.Spend(AskPeriod.sinceYear(y)) })
            expect("groceries fe $y", year(y) { AskQuery.Spend(it, groceries) })
            expect("groceries sanat $y", year(y) { AskQuery.Spend(it, groceries) })
            expect("groceries 3am $y", year(y) { AskQuery.Spend(it, groceries) })
            expect("groceries for $y", amountImplicit)
            expect("how much did I spend for $y", AskQuery.Spend(tm))
            expect("I paid 300 of $y", AskQuery.Spend(tm))
            expect("groceries سنة $y", year(y) { AskQuery.Spend(it, groceries) })
            expect("groceries لسنة $y", year(y) { AskQuery.Spend(it, groceries) })
            expect("how much did I spend in $y", year(y) { AskQuery.Spend(it) })
            expect("صرفت كام سنة $y", year(y) { AskQuery.Spend(it) })
            // a named period against a year, with an explicit compare word
            expect("this year vs $y", year(y) { AskQuery.Compare(AskPeriod.THIS_YEAR, it) })
            expect("groceries this month vs $y", year(y) { AskQuery.Compare(tm, it, groceries) })
            expect("groceries $y vs last year", year(y) { AskQuery.Compare(it, AskPeriod.LAST_YEAR, groceries) })
            // next to a month name or a day
            expect("groceries may $y", year(y) { AskQuery.Spend(AskPeriod.monthOfYear(5, y), groceries) })
            expect("سبتمبر $y", year(y) { AskQuery.Spend(AskPeriod.monthOfYear(9, y)) })
            expect("may 31 $y", year(y) { AskQuery.Spend(AskPeriod.dayRange(31, 31, 5, y)) })
            expect("31 may $y", year(y) { AskQuery.Spend(AskPeriod.dayRange(31, 31, 5, y)) })
            for (z in years) {
                val both: AskQuery =
                    if (readable(y) && readable(z)) AskQuery.Compare(AskPeriod.year(y), AskPeriod.year(z), groceries) else unreadable
                // an explicit two-year comparison: the bare numbers are years
                expect("groceries $y vs $z", both)
                expect("groceries $y versus $z", both)
                expect("groceries $y compared to $z", both)
                expect("groceries $y against $z", both)
                expect(
                    "البقالة سنة $y مقارنة بسنة $z",
                    if (readable(y) && readable(z)) {
                        AskQuery.Compare(AskPeriod.year(y), AskPeriod.year(z), AskSubject.Text("البقاله"))
                    } else {
                        unreadable
                    },
                )
                // cued on both sides
                expect("groceries in $y vs in $z", both)
                // explicit compare with "and" / "و"
                expect(
                    "مقارنة $y و $z",
                    if (readable(y) &&
                        readable(z)
                    ) {
                        AskQuery.Compare(AskPeriod.year(y), AskPeriod.year(z))
                    } else {
                        unreadable
                    },
                )
                expect(
                    "compare $y and $z",
                    if (readable(y) &&
                        readable(z)
                    ) {
                        AskQuery.Compare(AskPeriod.year(y), AskPeriod.year(z))
                    } else {
                        unreadable
                    },
                )
                // two bare numbers joined by "and" without a compare word cannot be read
                expect("groceries $y and $z", unreadable)
                expect("groceries in $y and in $z", unreadable)
            }
        }
        // two periods without a compare word never become a compare and never drop one
        for (q in listOf("april may june", "april june", "spent yesterday and today", "groceries this month and last month")) {
            expect(q, unreadable)
        }
        assertTrue("${failures.size} of $combinations combinations wrong:\n" + failures.take(25).joinToString("\n"), failures.isEmpty())
        println("YearQuestionsTest executed $combinations combinations")
        assertTrue("only $combinations combinations", combinations >= 1000)
    }
}
