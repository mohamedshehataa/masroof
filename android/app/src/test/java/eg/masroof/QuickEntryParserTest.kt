package eg.masroof

import eg.masroof.ingest.quick.EntryDraft
import eg.masroof.ingest.quick.EntryType
import eg.masroof.ingest.quick.QuickEntryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** STORY-200: the rules-only line parser. "Today" is Sunday 2026-11-08 (Cairo). */
class QuickEntryParserTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 8)
    private val yesterday: LocalDate = today.minusDays(1)

    private fun parse(line: String): EntryDraft = QuickEntryParser.parse(line, today)

    private fun check(
        line: String,
        minor: Long?,
        currency: String = "EGP",
        type: EntryType = EntryType.EXPENSE,
        category: String? = null,
        merchant: String? = null,
        date: LocalDate = today,
    ): EntryDraft {
        val d = parse(line)
        assertEquals("amount of [$line]", minor, d.amountMinor)
        assertEquals("currency of [$line]", currency, d.currency)
        assertEquals("type of [$line]", type, d.type)
        assertEquals("category of [$line]", category, d.categoryKey)
        assertEquals("merchant of [$line]", merchant, d.merchant)
        assertEquals("date of [$line]", date, d.date)
        return d
    }

    // ---- REQ-PARSE-098: the six rows of the story table ----

    @Test
    fun `REQ-PARSE-098 150 pounds on fuel`() {
        check("150 pounds on fuel", 15000, category = "car.fuel")
    }

    @Test
    fun `REQ-PARSE-098 spent 85_5 at talabat yesterday`() {
        check("spent 85.5 at talabat yesterday", 8550, category = "dining", merchant = "talabat", date = yesterday)
    }

    @Test
    fun `REQ-PARSE-098 20 dollars netflix`() {
        check("20 dollars netflix", 2000, "USD", category = "subscriptions", merchant = "netflix")
    }

    @Test
    fun `REQ-PARSE-098 received 12,000 salary is income`() {
        check("received 12,000 salary", 1_200_000, type = EntryType.INCOME)
    }

    @Test
    fun `REQ-PARSE-098 1_5k groceries`() {
        check("1.5k groceries", 150_000, category = "groceries")
    }

    @Test
    fun `REQ-PARSE-098 200 le uber on 3 oct`() {
        check("200 le uber on 3 oct", 20000, category = "transport", merchant = "uber", date = LocalDate.of(2026, 10, 3))
    }

    @Test
    fun `REQ-PARSE-098 minor units come from integer arithmetic`() {
        assertEquals(8550L, parse("85.5").amountMinor)
        assertEquals(125_075L, parse("1,250.75").amountMinor)
        assertEquals(150_000L, parse("1.5k").amountMinor)
        assertEquals(1250L, parse("12,5 coffee").amountMinor)
    }

    // ---- REQ-PARSE-098: unhappy paths ----

    @Test
    fun `REQ-PARSE-098 a line with no number has no amount`() {
        val d = check("fuel", null, category = "car.fuel")
        assertNull(d.note)
    }

    @Test
    fun `REQ-PARSE-098 two numbers take the one next to a currency word and the rest goes to the note`() {
        val d = check("5 coffees 90 le", 9000, category = "dining")
        assertEquals("5 coffees", d.note)
    }

    @Test
    fun `REQ-PARSE-098 two numbers without a currency word take the last one`() {
        val d = check("2 coffees 90", 9000, category = "dining")
        assertEquals("2 coffees", d.note)
    }

    @Test
    fun `REQ-PARSE-098 zero and overflow set no amount`() {
        check("0 fuel", null, category = "car.fuel")
        check("0.00 pounds", null)
        check("99999999999999999999 fuel", null, category = "car.fuel")
        check("10000000001", null)
        check("5000000000 million", null)
    }

    @Test
    fun `REQ-PARSE-098 the largest allowed amount is 1,000,000,000,000 minor units`() {
        assertEquals(1_000_000_000_000L, parse("10000000000 pounds").amountMinor)
    }

    @Test
    fun `REQ-PARSE-098 an amount with more than two decimals is not an amount and the line stays in the note`() {
        val d = check("1.234 fuel", null, category = "car.fuel")
        assertEquals("1.234 fuel", d.note)
    }

    @Test
    fun `REQ-PARSE-098 empty and blank lines give an empty draft`() {
        check("", null)
        check("   ", null)
        check("جنيه", null)
    }

    private val english =
        listOf(
            Triple("150", 15000L, null as String?),
            Triple("spent 200 on gas", 20000L, "car.fuel"),
            Triple("gift 100", 10000L, "gifts"),
            Triple("electricity bill 350", 35000L, "bills.electricity"),
            Triple("3 thousand rent", 300_000L, "rent"),
            Triple("1.5 million villa", 150_000_000L, null),
            Triple("1,250.75 rent", 125_075L, "rent"),
            Triple("L.E 90 taxi", 9000L, "transport"),
            Triple("buy shoes 450 pounds", 45000L, "clothing"),
            Triple("parking 20", 2000L, "car.parking"),
        )

    @Test
    fun `REQ-PARSE-098 more english lines`() {
        for ((line, minor, category) in english) check(line, minor, category = category)
    }

    @Test
    fun `REQ-PARSE-098 currency words symbols and codes`() {
        check("\$20 netflix", 2000, "USD", category = "subscriptions", merchant = "netflix")
        check("20\$ lunch", 2000, "USD", category = "dining")
        check("30 euros dinner", 3000, "EUR", category = "dining")
        check("100 riyals taxi", 10000, "SAR", category = "transport")
        check("75 dirhams groceries", 7500, "AED", category = "groceries")
        check("£15 gift", 1500, "GBP", category = "gifts")
        check("9 qar coffee", 900, "QAR", category = "dining")
        check("50 usd", 5000, "USD")
        check("50 egp", 5000)
    }

    @Test
    fun `REQ-PARSE-098 dates today yesterday on 3 oct and last friday in Cairo`() {
        check("50 pharmacy today", 5000, category = "health")
        check("yesterday 50 pharmacy", 5000, category = "health", date = yesterday)
        // 2026-11-08 is a Sunday: the Friday before it is the 6th.
        check("last friday 120 uber", 12000, category = "transport", merchant = "uber", date = LocalDate.of(2026, 11, 6))
        // A date still ahead of today means last year; a year typed out is kept.
        check("on 3 dec 40 cinema", 4000, category = "entertainment", date = LocalDate.of(2025, 12, 3))
        check("5 nov 2025 200 rent", 20000, category = "rent", date = LocalDate.of(2025, 11, 5))
        check("oct 3 60 coffee", 6000, category = "dining", date = LocalDate.of(2026, 10, 3))
        check("40 on 31 feb", 4000)
    }

    @Test
    fun `REQ-PARSE-098 merchant is the word after at and never a plain category word`() {
        check("spent 45 at zara", 4500, merchant = "zara")
        check("paid 60 at pizza hut", 6000, category = "dining")
        check("vodafone 100 recharge", 10000, category = "bills.mobile", merchant = "vodafone")
        check("received 500 bonus", 50000, type = EntryType.INCOME)
    }

    // ---- REQ-PARSE-099: the six rows of the story table ----

    @Test
    fun `REQ-PARSE-099 150 gneih benzin in Arabic-Indic digits`() {
        check("١٥٠ جنيه بنزين", 15000, category = "car.fuel")
    }

    @Test
    fun `REQ-PARSE-099 mia w khamseen at carrefour yesterday`() {
        check("دفعت مية وخمسين في كارفور امبارح", 15000, category = "groceries", merchant = "كارفور", date = yesterday)
    }

    @Test
    fun `REQ-PARSE-099 khamseen gneih mwaslat`() {
        check("خمسين جنيه مواصلات", 5000, category = "transport")
    }

    @Test
    fun `REQ-PARSE-099 alfen w nos eigar`() {
        check("ألفين ونص إيجار", 250_000, category = "rent")
    }

    @Test
    fun `REQ-PARSE-099 2bdt 20 alf mortab is income`() {
        check("قبضت ٢٠ ألف مرتب", 2_000_000, type = EntryType.INCOME)
    }

    @Test
    fun `REQ-PARSE-099 300 gneih talabat mixes scripts`() {
        check("300 جنيه talabat", 30000, category = "dining", merchant = "talabat")
    }

    // ---- REQ-PARSE-099: number words, digits and letters ----

    @Test
    fun `REQ-PARSE-099 number words join with waw and nos rob3 are halves and quarters`() {
        check("خمسة وعشرين جنيه قهوة", 2500, category = "dining")
        check("تلتمية وخمسين", 35000)
        check("ألف وخمسمية مياه", 150_000, category = "bills.water")
        check("ربعميه جنيه", 40000)
        check("ميتين جنيه بنزين امبارح", 20000, category = "car.fuel", date = yesterday)
        check("مليون جنيه", 100_000_000)
        check("تلات آلاف إيجار", 300_000, category = "rent")
        check("ألفين وربع", 225_000)
        check("مية ونص", 15000)
        check("اتنين جنيه", 200)
        check("حداشر جنيه", 1100)
        check("تسعتاشر جنيه", 1900)
        check("مليون وخمسمية ألف", 150_000_000)
        check("ألف جنيه إيجار", 100_000, category = "rent")
        check("عشرين جنيه", 2000)
    }

    @Test
    fun `REQ-PARSE-099 Arabic-Indic and Persian digits and separators`() {
        check("٥٠٠ جنيه إيجار", 50000, category = "rent")
        check("۲۰۰ جنيه", 20000)
        check("١٢٬٥٠٠ جنيه", 1_250_000)
        check("١٫٥ ألف بقالة", 150_000, category = "groceries")
        check("١٥٠ ج بنزين", 15000, category = "car.fuel")
        check("150ج بنزين", 15000, category = "car.fuel")
        check("٨٥٫٥ جنيه", 8550)
    }

    @Test
    fun `REQ-PARSE-099 currencies, income words and dates in Arabic`() {
        check("خمسين دولار بنزين", 5000, "USD", category = "car.fuel")
        check("٢٠ يورو", 2000, "EUR")
        check("مرتب ٨ ألف", 800_000, type = EntryType.INCOME)
        check("أول امبارح ٧٥ جنيه مطعم", 7500, category = "dining", date = today.minusDays(2))
        check("النهارده 100 جنيه اوبر", 10000, category = "transport", merchant = "اوبر")
        check("50 جنيه في 3 اكتوبر", 5000, date = LocalDate.of(2026, 10, 3))
        check("دفعت ٣٠٠ في صيدلية", 30000, category = "health")
    }

    @Test
    fun `REQ-PARSE-099 letters are folded for matching only and the merchant keeps its spelling`() {
        // إ/أ/آ -> ا, ى -> ي, ة -> ه in the keys; the typed merchant is shown as typed.
        val d = check("١٠٠ جنيه في كارفور", 10000, category = "groceries", merchant = "كارفور")
        assertNotNull(d.merchant)
        check("100 جنيه بقالة", 10000, category = "groceries")
        check("100 جنيه مستشفى", 10000, category = "health")
    }

    @Test
    fun `REQ-PARSE-099 an unparseable number phrase sets no amount and the words stay in the note`() {
        val line = "ألف ألف إيجار"
        val d = check(line, null, category = "rent")
        assertEquals(line, d.note)
        val two = check("مية ميتين بنزين", null, category = "car.fuel")
        assertEquals("مية ميتين بنزين", two.note)
    }

    @Test
    fun `REQ-PARSE-099 income never carries a category`() {
        val d = parse("قبضت مرتب بنزين 5000")
        assertEquals(EntryType.INCOME, d.type)
        assertNull(d.categoryKey)
    }

    @Test
    fun `REQ-PARSE-099 a huge line is bounded and does not throw`() {
        val d = parse("150 ".repeat(5000))
        assertNotNull(d)
    }

    // ---- fix round 1 ----

    @Test
    fun `REQ-PARSE-098 a 4-digit number next to a currency word or outside this and last year is the amount not the year`() {
        check("3 oct 1500 pounds uber", 150_000, category = "transport", merchant = "uber", date = LocalDate.of(2026, 10, 3))
        check("uber oct 3 2500 le", 250_000, category = "transport", merchant = "uber", date = LocalDate.of(2026, 10, 3))
        check("rent 5 nov 2000", 200_000, category = "rent", date = LocalDate.of(2026, 11, 5))
        check("on 3 oct 1200 le uber", 120_000, category = "transport", merchant = "uber", date = LocalDate.of(2026, 10, 3))
        check("3 oct 2026 pounds uber", 202_600, category = "transport", merchant = "uber", date = LocalDate.of(2026, 10, 3))
    }

    @Test
    fun `REQ-PARSE-098 a year is taken only when it is plausible, not the only number and never makes a future date`() {
        check("5 nov 2025 200 rent", 20000, category = "rent", date = LocalDate.of(2025, 11, 5))
        // Only candidate for the amount: it is the amount, the date has no year.
        check("rent 5 nov 2026", 202_600, category = "rent", date = LocalDate.of(2026, 11, 5))
        // 3 Dec 2026 would be in the future: 2026 is not a year, the date is last year's 3 Dec.
        check("3 dec 2026 50 fuel", 5000, category = "car.fuel", date = LocalDate.of(2025, 12, 3))
    }

    @Test
    fun `REQ-PARSE-098 malformed digit groups and minus signs give no amount`() {
        for (line in listOf("1,2345 fuel", "150.5.5 fuel", "1k5 pounds", "1 000 pounds", "-150 fuel", "\$-5", "\u2212150 fuel")) {
            assertNull("amount of [$line]", parse(line).amountMinor)
        }
        assertEquals(1_000_000L, parse("1,000 fuel").amountMinor?.let { it * 10 })
    }

    @Test
    fun `REQ-PARSE-098 paid beats an income word, got paid does not, and E pound is Egyptian`() {
        check("paid salary to nanny 3000", 300_000)
        check("got paid 5000 salary", 500_000, type = EntryType.INCOME)
        val d = check("E£150 fuel", 15000, category = "car.fuel")
        assertEquals(true, d.currencyStated)
    }

    @Test
    fun `REQ-PARSE-099 paid beats an income word in Arabic`() {
        check("دفعت مرتب الشغالة 3000", 300_000)
    }

    @Test
    fun `REQ-PARSE-099 colloquial thousands and the construct of one hundred`() {
        check("خمس تلاف جنيه", 500_000)
        check("تلت آلاف جنيه", 300_000)
        check("ميت جنيه", 10000)
        check("ميت الف", 10_000_000)
        check("اتنين تلاف", 200_000)
    }

    @Test
    fun `REQ-PARSE-099 a scale word that is not understood gives no amount and the line stays in the note`() {
        val d = check("خمس ملاين جنيه", null)
        assertEquals("خمس ملاين جنيه", d.note)
        check("خمس تلافين جنيه", 500)
    }

    @Test
    fun `REQ-PARSE-098 the merchant is capped at 80 characters and bidi controls are not kept in the note`() {
        val d = parse("50 at " + "a".repeat(400))
        assertEquals(80, d.merchant?.length)
        val n = parse("\u202Eألف ألف إيجار\u202C")
        assertNull(n.amountMinor)
        assertEquals(false, n.note.orEmpty().any { it in '\u202A'..'\u202E' })
    }

    // ---- fix round 2 ----

    @Test
    fun `REQ-PARSE-099 a half or quarter before a scale word is a fraction of the scale and N plus a half is N and a half`() {
        val rows =
            listOf(
                "نص مليون" to 50_000_000L,
                "ربع مليون" to 25_000_000L,
                "نص ألف" to 50_000L,
                "ربع ألف" to 25_000L,
                "خمسة ونص ألف" to 550_000L,
                "اتنين ونص مليون" to 250_000_000L,
                "مليون ونص" to 150_000_000L,
                "ألفين ونص" to 250_000L,
                "مية ونص" to 15_000L,
                "ألف إلا ربع" to 75_000L,
                "ألف إلا نص" to 50_000L,
                "مليون إلا ربع" to 75_000_000L,
            )
        for ((line, minor) in rows) assertEquals("amount of [$line]", minor, parse(line).amountMinor)
    }

    @Test
    fun `REQ-PARSE-099 a half or quarter with nothing to take it from, or an except with nothing after it, gives no amount`() {
        for (line in listOf("ربع", "نص", "ربع جنيه", "ألف إلا", "ألف إلا تلت", "إلا ربع", "ألف إلا ربع ألف")) {
            assertNull("amount of [$line]", parse(line).amountMinor)
        }
    }

    @Test
    fun `REQ-PARSE-098 every spelling of E pound and LE is Egyptian pounds`() {
        for (line in listOf("150E£ fuel", "£E 150 fuel", "E£ 150 fuel", "150 LE fuel", "L.E150 fuel", "150LE fuel", "E £150 fuel")) {
            val d = parse(line)
            assertEquals("amount of [$line]", 15000L, d.amountMinor)
            assertEquals("currency of [$line]", "EGP", d.currency)
            assertEquals("stated [$line]", true, d.currencyStated)
        }
        check("£150 fuel", 15000, "GBP", category = "car.fuel")
    }

    @Test
    fun `REQ-PARSE-098 a currency the form does not keep gives no amount and the line stays in the note`() {
        for (line in listOf("50 dinar fuel", "5 dinars coffee", "5 kwd coffee", "50 دينار بنزين", "100 yen sushi", "40 rupees tea")) {
            val d = parse(line)
            assertNull("amount of [$line]", d.amountMinor)
            assertEquals("note of [$line]", line, d.note)
            assertEquals(false, d.currencyStated)
        }
    }

    @Test
    fun `REQ-PARSE-098 an ISO date is a date and not negative numbers`() {
        check("50 on 2026-10-03", 5000, date = LocalDate.of(2026, 10, 3))
        check("2026-10-03 fuel 75", 7500, category = "car.fuel", date = LocalDate.of(2026, 10, 3))
        // Ahead of today or not a real day: still a date, never an amount; the entry keeps today.
        check("50 on 2027-01-01", 5000)
        check("50 on 2026-02-31", 5000)
    }
}
