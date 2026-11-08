package eg.masroof.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun t(text: String) = AskSubject.Text(text)

private val TM = AskPeriod.THIS_MONTH

private val SPEND_CASES: List<Pair<String, AskQuery>> =
    listOf(
        "how much did I spend on groceries this month" to AskQuery.Spend(TM, t("groceries")),
        "صرفت كام على البقالة الشهر ده" to AskQuery.Spend(TM, t("البقاله")),
        "groceries october" to AskQuery.Spend(AskPeriod.monthOfYear(10), t("groceries")),
        "How much did I spend on food today?" to AskQuery.Spend(AskPeriod.TODAY, t("food")),
        "spent on fuel yesterday" to AskQuery.Spend(AskPeriod.YESTERDAY, t("fuel")),
        "how much did I spend last week" to AskQuery.Spend(AskPeriod.LAST_WEEK),
        "spending this week on dining out" to AskQuery.Spend(AskPeriod.THIS_WEEK, t("dining out")),
        "how much on rent last month" to AskQuery.Spend(AskPeriod.LAST_MONTH, t("rent")),
        "total spending this year" to AskQuery.Spend(AskPeriod.THIS_YEAR),
        "how much did I spend in the last 10 days" to AskQuery.Spend(AskPeriod.lastDays(10)),
        "spent at talabat last 30 days" to AskQuery.Spend(AskPeriod.lastDays(30), t("talabat")),
        "how much did I spend in september" to AskQuery.Spend(AskPeriod.monthOfYear(9)),
        "how much did I spend in september 2025 on groceries" to AskQuery.Spend(AskPeriod.monthOfYear(9, 2025), t("groceries")),
        "صرفت كام في سبتمبر" to AskQuery.Spend(AskPeriod.monthOfYear(9)),
        "مصروفات شهر ٩" to AskQuery.Spend(AskPeriod.monthOfYear(9)),
        "صرفت كام على الاكل النهارده" to AskQuery.Spend(AskPeriod.TODAY, t("الاكل")),
        "كام صرفت على بنزين امبارح" to AskQuery.Spend(AskPeriod.YESTERDAY, t("بنزين")),
        "صرفت كام الاسبوع ده على المواصلات" to AskQuery.Spend(AskPeriod.THIS_WEEK, t("المواصلات")),
        "مصاريف الشهر اللي فات" to AskQuery.Spend(AskPeriod.LAST_MONTH),
        "صرفت كام السنة دي" to AskQuery.Spend(AskPeriod.THIS_YEAR),
        "how much did I spend on talabat this month" to AskQuery.Spend(TM, t("talabat")),
        "sarft kam 3ala el akl el shahr da" to AskQuery.Spend(TM, t("akl")),
        "sarft kam embare7" to AskQuery.Spend(AskPeriod.YESTERDAY),
        "كام صرفت على الأكل آخر ٧ أيام" to AskQuery.Spend(AskPeriod.lastDays(7), t("الاكل")),
        "How much did I spend on electricity this month" to AskQuery.Spend(TM, t("electricity")),
        "المصروف على الكهرباء الشهر ده" to AskQuery.Spend(TM, t("الكهرباء")),
        "total this month" to AskQuery.Spend(TM),
        "how much did I pay for internet this month" to AskQuery.Spend(TM, t("internet")),
        "صرفت كام على groceries الشهر ده" to AskQuery.Spend(TM, t("groceries")),
        "how much sarft 3ala talabat" to AskQuery.Spend(TM, t("talabat")),
        "صرفت كام في آخر ١٤ يوم" to AskQuery.Spend(AskPeriod.lastDays(14)),
        "صرفت كام في شهر ١٢" to AskQuery.Spend(AskPeriod.monthOfYear(12)),
        "groceries october 2025" to AskQuery.Spend(AskPeriod.monthOfYear(10, 2025), t("groceries")),
        "expenses today" to AskQuery.Spend(AskPeriod.TODAY),
        "كام صرفت على المطاعم الاسبوع اللي فات" to AskQuery.Spend(AskPeriod.LAST_WEEK, t("المطاعم")),
        "how much did I spend in dec" to AskQuery.Spend(AskPeriod.monthOfYear(12)),
        "How much did I spend on Careem in march?" to AskQuery.Spend(AskPeriod.monthOfYear(3), t("careem")),
    )

private val BREAKDOWN_CASES: List<Pair<String, AskQuery>> =
    listOf(
        "where did my money go this month" to AskQuery.Breakdown(TM),
        "فلوسي راحت فين" to AskQuery.Breakdown(TM),
        "فلوسي راحت فين الشهر اللي فات" to AskQuery.Breakdown(AskPeriod.LAST_MONTH),
        "spending breakdown" to AskQuery.Breakdown(TM),
        "breakdown by category last month" to AskQuery.Breakdown(AskPeriod.LAST_MONTH),
        "where does my money go" to AskQuery.Breakdown(TM),
        "floosy rahet feen" to AskQuery.Breakdown(TM),
        "فلوسي بتروح فين الاسبوع ده" to AskQuery.Breakdown(AskPeriod.THIS_WEEK),
        "صرفت على ايه الشهر ده" to AskQuery.Breakdown(TM),
        "what did I spend on this month" to AskQuery.Breakdown(TM),
        "what did I spend on yesterday" to AskQuery.Breakdown(AskPeriod.YESTERDAY),
        "where did my money go in october" to AskQuery.Breakdown(AskPeriod.monthOfYear(10)),
        "biggest category this month" to AskQuery.Breakdown(TM),
        "where did my money go last 7 days" to AskQuery.Breakdown(AskPeriod.lastDays(7)),
        "اتصرفت فين الفلوس" to AskQuery.Breakdown(TM),
    )

private val COMPARE_CASES: List<Pair<String, AskQuery>> =
    listOf(
        "this month vs last month" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "this week vs last week" to AskQuery.Compare(AskPeriod.THIS_WEEK, AskPeriod.LAST_WEEK),
        "الشهر ده مقارنة باللي فات" to AskQuery.Compare(TM),
        "compare this month with last month" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "compare with last month" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "groceries this month vs last month" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH, t("groceries")),
        "compare october and september" to AskQuery.Compare(AskPeriod.monthOfYear(10), AskPeriod.monthOfYear(9)),
        "هل صرفت اكتر من الشهر اللي فات" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "did I spend more than last month" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "today vs yesterday" to AskQuery.Compare(AskPeriod.TODAY, AskPeriod.YESTERDAY),
        "قارن الشهر ده بالشهر اللي فات" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "moqarna el shahr da bel shahr el madi" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "this month versus last month" to AskQuery.Compare(TM, AskPeriod.LAST_MONTH),
        "الاسبوع ده مقارنة بالاسبوع اللي فات" to AskQuery.Compare(AskPeriod.THIS_WEEK, AskPeriod.LAST_WEEK),
        "compare this year vs last year" to AskQuery.Compare(AskPeriod.THIS_YEAR, AskPeriod.LAST_YEAR),
        "compare week" to AskQuery.Compare(AskPeriod.THIS_WEEK),
        "comparison" to AskQuery.Compare(TM),
    )

private val MERCHANT_CASES: List<Pair<String, AskQuery>> =
    listOf(
        "how often do I order talabat" to AskQuery.MerchantVisits(TM, "talabat"),
        "طلبت من طلبات كام مرة" to AskQuery.MerchantVisits(TM, "طلبات"),
        "how many times did I go to amazon last month" to AskQuery.MerchantVisits(AskPeriod.LAST_MONTH, "amazon"),
        "كام مرة روحت كارفور الشهر اللي فات" to AskQuery.MerchantVisits(AskPeriod.LAST_MONTH, "كارفور"),
        "kam mara talabt men talabat" to AskQuery.MerchantVisits(TM, "talabat"),
        "how often do I eat at starbucks this week" to AskQuery.MerchantVisits(AskPeriod.THIS_WEEK, "starbucks"),
        "top places" to AskQuery.TopMerchants(TM),
        "أكتر أماكن بصرف فيها" to AskQuery.TopMerchants(TM),
        "top merchants last month" to AskQuery.TopMerchants(AskPeriod.LAST_MONTH),
        "top shops this week" to AskQuery.TopMerchants(AskPeriod.THIS_WEEK),
        "where do I shop the most" to AskQuery.TopMerchants(TM),
        "most visited places this year" to AskQuery.TopMerchants(AskPeriod.THIS_YEAR),
        "aktar amaken ana basraf feha" to AskQuery.TopMerchants(TM),
        "how often" to AskQuery.NotUnderstood,
    )

private val PAYDAY_CASES: List<Pair<String, AskQuery>> =
    listOf(
        "what's left until payday" to AskQuery.Payday,
        "فاضل كام لحد القبض" to AskQuery.Payday,
        "how much is left until payday" to AskQuery.Payday,
        "days to payday" to AskQuery.Payday,
        "fadel kam l7d el2abd" to AskQuery.Payday,
        "payday" to AskQuery.Payday,
        "money left" to AskQuery.Payday,
        "فاضلي كام" to AskQuery.Payday,
        "biggest expense this month" to AskQuery.Biggest(TM),
        "أكبر مصروف الشهر ده" to AskQuery.Biggest(TM),
        "largest purchase last month" to AskQuery.Biggest(AskPeriod.LAST_MONTH),
        "akbar masrof embare7" to AskQuery.Biggest(AskPeriod.YESTERDAY),
        "most expensive thing this week" to AskQuery.Biggest(AskPeriod.THIS_WEEK),
    )

private val ROAST_WHY_CASES: List<Pair<String, AskQuery>> =
    listOf(
        "roast me" to AskQuery.Roast,
        "اتريق عليا" to AskQuery.Roast,
        "Roast me please" to AskQuery.Roast,
        "why is groceries so high" to AskQuery.Why(TM, t("groceries")),
        "ليه الاكل غالي اوي" to AskQuery.Why(TM, t("الاكل")),
        "why is dining out so high this month" to AskQuery.Why(TM, t("dining out")),
        "why is it so high" to AskQuery.NotUnderstood,
        "" to AskQuery.NotUnderstood,
        "    " to AskQuery.NotUnderstood,
        "12345" to AskQuery.NotUnderstood,
        "؟؟؟" to AskQuery.NotUnderstood,
        "what is the weather today" to AskQuery.NotUnderstood,
        "ايه الطقس النهارده" to AskQuery.NotUnderstood,
        "how are you" to AskQuery.NotUnderstood,
        "who won the match yesterday" to AskQuery.NotUnderstood,
        "last 0 days" to AskQuery.PeriodUnreadable,
        "how much did I spend last 400 days" to AskQuery.PeriodUnreadable,
        "how much did I spend in month 13" to AskQuery.PeriodUnreadable,
        "why" to AskQuery.NotUnderstood,
    )

private val PERIOD_CASES: List<Pair<String, AskQuery>> =
    listOf(
        "how much did I spend last year" to AskQuery.Spend(AskPeriod.LAST_YEAR),
        "groceries in 2025" to AskQuery.Spend(AskPeriod.year(2025), t("groceries")),
        "صرفت كام سنة 2025" to AskQuery.Spend(AskPeriod.year(2025)),
        "صرفت كام السنة اللي فاتت" to AskQuery.Spend(AskPeriod.LAST_YEAR),
        "spent 2 months ago" to AskQuery.Spend(AskPeriod.monthsAgo(2)),
        "صرفت كام من شهرين" to AskQuery.Spend(AskPeriod.monthsAgo(2)),
        "صرفت كام من 3 شهور" to AskQuery.Spend(AskPeriod.monthsAgo(3)),
        "groceries from 1 to 15 october" to AskQuery.Spend(AskPeriod.dayRange(1, 15, 10), t("groceries")),
        "صرفت كام من 1 ل 15 اكتوبر" to AskQuery.Spend(AskPeriod.dayRange(1, 15, 10)),
        "how much did I spend past 2 weeks" to AskQuery.Spend(AskPeriod.lastDays(14)),
        "I may have spent too much on groceries" to AskQuery.Spend(TM, t("groceries")),
        "how much did I spend in may" to AskQuery.Spend(AskPeriod.monthOfYear(5)),
        "groceries may 2025" to AskQuery.Spend(AskPeriod.monthOfYear(5, 2025), t("groceries")),
        "may" to AskQuery.Spend(AskPeriod.monthOfYear(5)),
        "how much did I spend on mar 5" to AskQuery.Spend(AskPeriod.monthOfYear(3)),
        "بشهر 8 صرفت كام" to AskQuery.Spend(AskPeriod.monthOfYear(8)),
        "groceries may" to AskQuery.Spend(AskPeriod.monthOfYear(5), t("groceries")),
        "may groceries" to AskQuery.Spend(AskPeriod.monthOfYear(5), t("groceries")),
        "spending may" to AskQuery.Spend(AskPeriod.monthOfYear(5)),
        "how much in may" to AskQuery.Spend(AskPeriod.monthOfYear(5)),
        "may I know how much I spent today" to AskQuery.Spend(AskPeriod.TODAY),
        "march groceries" to AskQuery.Spend(AskPeriod.monthOfYear(3), t("groceries")),
        "spent at mar mina pharmacy" to AskQuery.Spend(TM, t("mar mina pharmacy")),
        "groceries may spending" to AskQuery.PeriodUnreadable,
        "did I spend more than 2050 on groceries this month" to AskQuery.Spend(TM, t("groceries")),
        "spent 2000 on groceries" to AskQuery.Spend(AskPeriod.year(2000), t("groceries")),
        "groceries in 2050" to AskQuery.PeriodUnreadable,
        "groceries 2027" to AskQuery.PeriodUnreadable,
        "spent 2030" to AskQuery.PeriodUnreadable,
        "groceries 2025 vs 2024" to AskQuery.Compare(AskPeriod.year(2025), AskPeriod.year(2024), t("groceries")),
        "صرفت كام ع الاكل" to AskQuery.Spend(TM, t("الاكل")),
        "صرفت كام عال بنزين" to AskQuery.Spend(TM, t("بنزين")),
        "groceries vs dining this month" to AskQuery.Compare(TM, null, t("groceries dining")),
        "roast chicken" to AskQuery.Spend(TM, t("chicken"), implicit = true),
        "how much did I spend in 1950" to AskQuery.PeriodUnreadable,
        "spent 3 weeks ago" to AskQuery.PeriodUnreadable,
        "spent 5 days ago" to AskQuery.PeriodUnreadable,
        "spent last 2 months" to AskQuery.PeriodUnreadable,
        "spent from 20 to 5 october" to AskQuery.PeriodUnreadable,
        "spent from 5 to 7" to AskQuery.PeriodUnreadable,
        "صرفت كام منذ زمان" to AskQuery.PeriodUnreadable,
    )

class QuestionParserTest {
    private fun check(cases: List<Pair<String, AskQuery>>) {
        val failures =
            cases.mapNotNull { (q, expected) ->
                val actual = QuestionParser.parse(q)
                if (actual == expected) null else "[$q] expected $expected but was $actual"
            }
        assertTrue("${failures.size} of ${cases.size} questions parsed wrongly:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `REQ-AN-160 spend questions in English, Egyptian Arabic, Franco and mixed text become typed Spend queries`() = check(SPEND_CASES)

    @Test
    fun `REQ-AN-160 Arabic-Indic digits and tashkeel and alef or ya variants fold to the same question`() {
        val plain = QuestionParser.parse("صرفت كام على البقاله الشهر ده")
        assertEquals(plain, QuestionParser.parse("صَرَفْتُ كام على البقالة الشهر دة"))
        assertEquals(QuestionParser.parse("spent last 12 days"), QuestionParser.parse("spent last ١٢ days"))
        assertEquals(AskQuery.Spend(AskPeriod.lastDays(12)), QuestionParser.parse("صرفت كام اخر ١٢ يوم"))
        assertEquals(QuestionParser.parse("أكتر أماكن بصرف فيها"), QuestionParser.parse("اكتر اماكن بصرف فيها"))
    }

    @Test
    fun `REQ-AN-161 where did my money go questions become Breakdown queries`() = check(BREAKDOWN_CASES)

    @Test
    fun `REQ-AN-162 comparison questions become Compare queries`() = check(COMPARE_CASES)

    @Test
    fun `REQ-AN-163 merchant frequency and top places questions`() = check(MERCHANT_CASES)

    @Test
    fun `REQ-AN-164 payday and biggest expense questions`() = check(PAYDAY_CASES)

    @Test
    fun `REQ-AN-165 roast and why questions, and questions that are not understood`() = check(ROAST_WHY_CASES)

    @Test
    fun `REQ-AN-165 a bare word with no period or spending word is an implicit spend question the engine may reject`() {
        assertEquals(AskQuery.Spend(TM, t("groceries"), implicit = true), QuestionParser.parse("groceries"))
        assertEquals(AskQuery.Spend(TM, t("hello"), implicit = true), QuestionParser.parse("hello"))
        assertEquals(AskQuery.Spend(TM, t("بنزين"), implicit = true), QuestionParser.parse("بنزين"))
    }

    @Test
    fun `REQ-AN-165 hostile or huge input never throws`() {
        val inputs =
            listOf(
                "a".repeat(100_000),
                "\u0000\u0001😀".repeat(500),
                "(((((((((((((((((((((",
                "last 99999999999999999999 days",
                "month 99999999999999999999",
                "this month ".repeat(5_000),
            )
        inputs.forEach { QuestionParser.parse(it) }
    }

    @Test
    fun `REQ-AN-160 years, months ago, day ranges, weeks and ambiguous month words like may are read with a cue only`() =
        check(PERIOD_CASES)

    @Test
    fun `REQ-AN-160 the tables hold at least 60 distinct questions`() {
        val all = SPEND_CASES + BREAKDOWN_CASES + COMPARE_CASES + MERCHANT_CASES + PAYDAY_CASES + ROAST_WHY_CASES + PERIOD_CASES
        assertTrue("only ${all.size} questions", all.map { it.first }.distinct().size >= 60)
    }

    /**
     * The bug class: a year-like token next to vs / and / more than / a currency word. For every template and every year
     * (1990 .. 2099, 2026 being the current year) the result is the year period(s), the amount-ignoring default, or
     * PeriodUnreadable - never a silent this-month answer and never a dropped year.
     */
    @Test
    fun `REQ-AN-160 generated year questions are never a silent this-month answer or a dropped year`() {
        val now = 2026
        val years = listOf(1990, 1995, 2000, 2005, 2010, 2020, 2024, 2025, 2026, 2027, 2030, 2099)

        fun readable(y: Int) = y in (now - 30)..now
        val groceries = t("groceries")
        val unreadable = AskQuery.PeriodUnreadable
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
        for (y in years) {
            val one = if (readable(y)) AskPeriod.year(y) else null

            fun single(query: AskQuery?) = query ?: unreadable
            expect("groceries $y", single(one?.let { AskQuery.Spend(it, groceries) }))
            expect("$y groceries", single(one?.let { AskQuery.Spend(it, groceries) }))
            expect("groceries in $y", single(one?.let { AskQuery.Spend(it, groceries) }))
            expect("spent $y", single(one?.let { AskQuery.Spend(it) }))
            expect("how much did I spend in $y", single(one?.let { AskQuery.Spend(it) }))
            expect("صرفت كام سنة $y", single(one?.let { AskQuery.Spend(it) }))
            expect("more than $y on groceries this month", AskQuery.Spend(TM, groceries))
            expect("less than $y on groceries this month", AskQuery.Spend(TM, groceries))
            expect("$y egp on groceries", AskQuery.Spend(TM, groceries, implicit = true))
            expect("$y جنيه على البقالة الشهر ده", AskQuery.Spend(TM, t("البقاله")))
            for (z in years) {
                val both = if (readable(y) && readable(z)) AskQuery.Compare(AskPeriod.year(y), AskPeriod.year(z), groceries) else unreadable
                expect("groceries $y vs $z", both)
                expect("groceries $y and $z", both)
                expect("groceries $y compared to $z", both)
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
            }
        }
        assertTrue("${failures.size} of $combinations combinations wrong:\n" + failures.take(25).joinToString("\n"), failures.isEmpty())
        assertTrue("only $combinations combinations", combinations >= 400)
    }
}
