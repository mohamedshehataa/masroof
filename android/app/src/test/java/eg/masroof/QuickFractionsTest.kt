package eg.masroof

import eg.masroof.ingest.quick.QuickEntryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * STORY-200 REQ-PARSE-099: fraction words (نص ربع تلت إلا) are a whitelist. Inside it the amount is exact, outside it there is no
 * amount: a missing amount is fine, a wrong one never is. All values below are minor units (piasters).
 */
class QuickFractionsTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 8)

    private fun amount(line: String): Long? = QuickEntryParser.parse(line, today).amountMinor

    @Test
    fun `REQ-PARSE-099 every whitelisted fraction form gives the exact amount`() {
        val rows =
            listOf(
                // <number> و<half|quarter>: half or quarter of the last unit
                "ألفين ونص" to 250_000L,
                "مية ونص" to 15_000L,
                "مليون ونص" to 150_000_000L,
                "عشرة ونص" to 1_050L,
                "عشرين ونص" to 2_050L,
                "اتنين ونص" to 250L,
                "خمسة وربع" to 525L,
                "ألف ومية ونص" to 115_000L,
                "ألف وخمسمية وربع" to 152_500L,
                "خمس تلاف ونص" to 550_000L,
                "ميتين ونص" to 25_000L,
                // <half|quarter|third> <scale|مية>
                "نص مليون" to 50_000_000L,
                "ربع مليون" to 25_000_000L,
                "نص ألف" to 50_000L,
                "ربع ألف" to 25_000L,
                "نص مية" to 5_000L,
                "ربع مية" to 2_500L,
                // <N> و<half|quarter> <scale>, N below the scale, including hundreds
                "مية ونص ألف" to 15_000_000L,
                "ميتين ونص ألف" to 25_000_000L,
                "خمسة ونص ألف" to 550_000L,
                "خمسة وربع ألف" to 525_000L,
                "اتنين ونص مليون" to 250_000_000L,
                "عشرين ونص ألف" to 2_050_000L,
                "خمسمية ونص ألف" to 55_000_000L,
                "خمسة ونص تلاف" to 550_000L,
                // <scale|مية> إلا <half|quarter|third>
                "ألف إلا ربع" to 75_000L,
                "ألف إلا نص" to 50_000L,
                "مليون إلا ربع" to 75_000_000L,
                "مية إلا ربع" to 7_500L,
                "مية إلا نص" to 5_000L,
                // a plain "تلت" is 3 only straight before a plural scale
                "تلت تلاف" to 300_000L,
                "واحد ونص" to 150L,
            )
        for ((line, minor) in rows) {
            assertEquals("amount of [$line]", minor, amount(line))
        }
    }

    @Test
    fun `REQ-PARSE-099 every fraction construct outside the whitelist gives no amount`() {
        val rows =
            listOf(
                "نص",
                "ربع",
                "تلت",
                "إلا",
                "ونص",
                "ربع جنيه",
                "نص جنيه",
                "نص خمسة",
                "ربع ميتين",
                "نص ألفين",
                "نص تلاف",
                "تلت مليون",
                "تلت ألف",
                "تلت مية",
                "ألف إلا تلت",
                "مليون إلا تلت",
                "ألف إلا",
                "إلا ربع",
                "ألف إلا ربع ألف",
                "ميتين إلا ربع",
                "خمسة إلا ربع",
                "ألفين إلا نص",
                "خمسة نص",
                "عشرين نص",
                "ألفين نص",
                "خمسة ونص ألفين",
                "ألفين ونص ألف",
                "ألف ونص ألف",
                "مليون ونص ألف",
                "ربع ونص",
                "نص ونص",
                "خمسة ونص ونص",
                "خمسة ونص ونص ألف",
                "تلت وربع",
                "خمسة وتلت",
                "عشرين وتلت",
                "واحد تلت",
                "خمسة ونص خمسة",
                "خمسة ونص مية",
                "ألف ونص مية",
                "خمسة ونص ربع",
                "نص و مليون",
                "مية و إلا ربع",
                "ألفين ونص تلاف",
            )
        for (line in rows) assertNull("amount of [$line]", amount(line))
    }

    @Test
    fun `REQ-PARSE-099 a half without the waw after a number is not 50 and a half`() {
        assertNull(amount("خمسين نص كيلو"))
        assertNull(amount("خمسة نص"))
    }

    @Test
    fun `REQ-PARSE-099 fraction words outside a number never leave a partial amount`() {
        // A fraction word beside a number makes the whole phrase unreadable instead of reading the number without it.
        assertNull(amount("نص خمسة"))
        assertNull(amount("خمسة إلا"))
        assertNull(amount("نص و و خمسة"))
        assertEquals(500L, amount("إلا خمسة"))
    }

    // ---- exhaustive check against an independent reference of the whitelist grammar ----

    private val tokens =
        listOf(
            "واحد",
            "اتنين",
            "خمسة",
            "عشرة",
            "عشرين",
            "مية",
            "ميتين",
            "خمسمية",
            "ألف",
            "ألفين",
            "تلاف",
            "مليون",
            "و",
            "نص",
            "ربع",
            "تلت",
            "إلا",
        )
    private val fractionWords = setOf("نص", "ربع", "تلت", "إلا")

    @Test
    fun `REQ-PARSE-099 every phrase of up to 4 tokens with a fraction word matches the whitelist reference`() {
        var checked = 0
        var withAmount = 0
        val bad = mutableListOf<String>()

        fun visit(phrase: List<String>) {
            if (phrase.any { it in fractionWords }) {
                checked++
                val line = phrase.joinToString(" ")
                val got = amount(line)
                val want = Reference.value(phrase)
                if (got != null) withAmount++
                if (got != want && bad.size < 20) bad += "[$line] parser=$got reference=$want"
            }
            if (phrase.size < 4) for (t in tokens) visit(phrase + t)
        }
        for (t in tokens) visit(listOf(t))
        assertTrue("disagreements: $bad", bad.isEmpty())
        // 17 + 17^2 + 17^3 + 17^4 phrases, all but those without a fraction word.
        assertTrue("checked $checked", checked > 40_000)
        assertTrue("some whitelisted phrases must give an amount: $withAmount", withAmount > 100)
        println("QUICK-FRACTIONS combinations checked=$checked withAmount=$withAmount")
    }

    private val phraseTokens =
        tokens + listOf("ونص", "وربع", "جنيه", "pounds")
    private val currencyWords = setOf("جنيه", "pounds")

    // The value of ONE number phrase (words, "و", fraction words, at most one currency word) against the independent reference.
    // Which number of a line is the amount is a different question, covered by the table test below.
    @Test
    fun `REQ-PARSE-099 the value of a single number phrase with a currency word matches the whitelist reference`() {
        var checked = 0
        var withAmount = 0
        val bad = mutableListOf<String>()

        fun check(phrase: List<String>) {
            val body = singlePhraseBody(phrase) ?: return
            if (phrase.none { it in fractionWords || it == "ونص" || it == "وربع" }) return
            checked++
            val got = amount(phrase.joinToString(" "))
            val want = Reference.value(body)?.takeIf { it in 1..1_000_000_000_000L }
            if (got != null) withAmount++
            if (got != want && bad.size < 20) bad += "[${phrase.joinToString(" ")}] parser=$got reference=$want"
        }

        fun visit(phrase: List<String>) {
            check(phrase)
            if (phrase.size < 4) for (t in phraseTokens) visit(phrase + t)
        }
        for (t in phraseTokens) visit(listOf(t))
        val exhaustive = checked
        val random = java.util.Random(20_261_108L)
        repeat(300_000) { check(List(5) { phraseTokens[random.nextInt(phraseTokens.size)] }) }
        assertTrue("disagreements: $bad", bad.isEmpty())
        assertTrue("exhaustive checked $exhaustive", exhaustive > 100_000)
        assertTrue("some phrases must give an amount: $withAmount", withAmount > 1_000)
        println("QUICK-FRACTIONS-PHRASES checked=$checked (exhaustive up to 4 tokens: $exhaustive) withAmount=$withAmount")
    }

    /**
     * The words of a phrase that is one number: glued "ونص" split, the currency word removed when it is leading, trailing or
     * sits right after the number and before a fraction word ("خمسين جنيه ونص"), and leading "إلا" (the word "only") dropped.
     * Null for phrases where the currency word splits the number in two (those are run-selection cases).
     */
    private fun singlePhraseBody(phrase: List<String>): List<String>? {
        val t =
            phrase.flatMap {
                if (it == "ونص") {
                    listOf("و", "نص")
                } else if (it == "وربع") {
                    listOf("و", "ربع")
                } else {
                    listOf(it)
                }
            }
        val cur = t.indices.filter { t[it] in currencyWords }
        if (cur.size > 1) return null
        val body = t.toMutableList()
        if (cur.size == 1) {
            val c = cur[0]
            val next = t.drop(c + 1).firstOrNull { it != "و" }
            val bridge = c > 0 && t[c - 1] != "و" && (next == "نص" || next == "ربع" || next == "إلا")
            if (!(c == 0 || c == t.lastIndex || bridge)) return null
            body.removeAt(c)
        }
        return body.dropWhile { it == "و" || it == "إلا" }
    }

    @Test
    fun `REQ-PARSE-099 which number of a line is the amount, with ordinary words around it`() {
        val rows =
            listOf(
                "ألف جنيه ونص إيجار 3 شهور" to 150_000L,
                "150 جنيه ونص لـ 2 كيلو" to 15_050L,
                "ألف جنيه إلا ربع" to 75_000L,
                "مدفعتش إلا 50" to 5_000L,
                "إلا 50 جنيه" to 5_000L,
                "50 جنيه ونص 2 قهوة" to 5_050L,
                "2 قهوة 90" to 9_000L,
                "5 كيلو لحمة 300 جنيه" to 30_000L,
                "قهوة 2 بنزين 150" to 15_000L,
                "نص كيلو لحمة 50 جنيه" to 5_000L,
                "إيجار 3 شهور 5000 جنيه" to 500_000L,
                "بنزين 40 لتر 300" to 30_000L,
                "300 جنيه بنزين 40 لتر" to 30_000L,
                "ربع كيلو جبنة 60" to 6_000L,
                "دفعت ألفين ونص جنيه إيجار 3 شهور" to 250_000L,
                "50 pounds ونص 2 coffees" to 5_050L,
            )
        for ((line, minor) in rows) assertEquals("amount of [$line]", minor, amount(line))
        assertEquals("2 قهوة", QuickEntryParser.parse("50 جنيه ونص 2 قهوة", today).note)
        assertEquals("إيجار 3 شهور", QuickEntryParser.parse("ألف جنيه ونص إيجار 3 شهور", today).note)
        assertNull(amount("50 جنيه نص كيلو"))
    }

    @Test
    fun `REQ-PARSE-099 a currency word between a number and its half`() {
        assertEquals(5_050L, amount("50 جنيه ونص"))
        assertEquals(5_050L, amount("خمسين جنيه ونص"))
        assertEquals(150_000L, amount("ألف جنيه ونص"))
        // A quarter of the last unit spoken (a hundred), like "مية وربع" = 125.
        assertEquals(12_500L, amount("مية جنيه وربع"))
        assertEquals(5_050L, amount("50 pounds و نص"))
        assertNull(amount("50 جنيه نص"))
        assertNull(amount("نص جنيه"))
        assertNull(amount("جنيه ونص"))
        val d = QuickEntryParser.parse("خمسين جنيه ونص", today)
        assertEquals("EGP", d.currency)
        assertEquals(true, d.currencyStated)
    }

    @Test
    fun `REQ-PARSE-099 k only follows digits and a fraction word right before digits is no amount`() {
        for (line in listOf("k", "K", "50 fuel k", "ك", "خمسة k", "خمسة ك", "5 k k")) {
            assertEquals("amount of [$line]", if (line == "50 fuel k") 5_000L else null, amount(line))
        }
        assertEquals(500_000L, amount("5k"))
        assertEquals(150_000L, amount("1.5k"))
        assertEquals(500_000L, amount("5ك"))
        for (line in listOf("نص ٥٠٠", "نص 500", "ربع 50")) assertNull("amount of [$line]", amount(line))
    }

    /** A small independent reader of the intended grammar, working on the words (not on the parser's pieces). */
    private object Reference {
        private val ones = mapOf("واحد" to 1L, "اتنين" to 2L, "خمسة" to 5L, "تلت" to 3L)
        private val tens = mapOf("عشرة" to 10L, "عشرين" to 20L)
        private val hundreds = mapOf("مية" to 100L, "ميتين" to 200L, "خمسمية" to 500L)
        private val scaleFactor = mapOf("ألف" to 1_000L, "تلاف" to 1_000L, "مليون" to 1_000_000L, "k" to 1_000L)
        val digitMinor = mapOf("5" to 500L, "50" to 5_000L, "500" to 50_000L, "1.5" to 150L)
        private val divisor = mapOf("نص" to 2L, "ربع" to 4L, "تلت" to 3L)
        private const val AND = "و"
        private const val EXCEPT = "إلا"
        private const val PER = 100L

        /** (value, size of the last unit) in piasters for a fraction-free number made of [words] (no "و"), or null. */
        @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        fun number(words: List<String>): Pair<Long, Long>? {
            var total = 0L
            var cur = 0L
            var seen = 0
            var prevDigit = false
            var lastUnit = 0L
            var lastScale = Long.MAX_VALUE
            for (w in words) {
                val afterDigit = prevDigit
                prevDigit = w in digitMinor
                digitMinor[w]?.let {
                    cur += it
                    seen = 7
                    lastUnit = PER
                    continue
                }
                val bit =
                    when (w) {
                        in hundreds -> 1
                        in tens -> 2
                        in ones -> 4
                        else -> 0
                    }
                if (bit != 0) {
                    if (seen and bit != 0) return null
                    seen = seen or bit
                    cur += (hundreds[w] ?: tens[w] ?: ones[w]!!) * PER
                    lastUnit = if (bit == 1) 100 * PER else PER
                    continue
                }
                val factor = if (w == "ألفين") 1_000L else scaleFactor[w] ?: return null
                if (factor >= lastScale) return null
                lastScale = factor
                val base =
                    when (w) {
                        "k" -> if (!afterDigit || seen == 0) return null else cur
                        "تلاف" -> if (seen == 0) return null else cur
                        "ألفين" -> if (seen == 0) 2 * PER else return null
                        else -> if (seen == 0) PER else cur
                    }
                total += base * factor
                cur = 0
                seen = 0
                lastUnit = factor * PER
            }
            return (total + cur).takeIf { it > 0 }?.let { it to lastUnit }
        }

        private fun unit(w: String): Long? =
            when (w) {
                "ألف" -> 1_000L
                "مليون" -> 1_000_000L
                "مية" -> 100L
                else -> null
            }

        @Suppress("CyclomaticComplexMethod")
        fun value(raw: List<String>): Long? {
            // Leading and trailing "و" are not part of a number.
            // A leading "إلا" is the word "only", not a fraction.
            val t = raw.dropWhile { it == AND || it == EXCEPT }.dropLastWhile { it == AND }
            val plain = t.filter { it != AND }
            val noFractionButThird = plain.none { it == "نص" || it == "ربع" || it == EXCEPT }
            if (noFractionButThird && t.indices.filter { t[it] == "تلت" }.all { t.getOrNull(it + 1) == "تلاف" }) {
                // "تلت" is the count 3 straight before a plural scale.
                return number(plain.map { it })?.first
            }
            if (AND !in t) {
                if (t.size == 2 && divisor[t[0]] != null) {
                    val base = (unit(t[1]) ?: return null) * PER
                    return if (base % divisor[t[0]]!! == 0L) base / divisor[t[0]]!! else null
                }
                if (t.size == 3 && t[1] == EXCEPT) {
                    val d = divisor[t[2]] ?: return null
                    val base = (unit(t[0]) ?: return null) * PER
                    return if (base % d == 0L) base - base / d else null
                }
            }
            val halves = t.indices.filter { t[it] == "نص" || t[it] == "ربع" }
            if (halves.size != 1 || "تلت" in t || EXCEPT in t) return null
            val k = halves[0]
            if (k == 0 || t[k - 1] != AND) return null
            val prefix = t.subList(0, k).dropLastWhile { it == AND }.filter { it != AND }
            val d = divisor[t[k]]!!
            val (value, lastUnit) = number(prefix) ?: return null
            if (lastUnit % d != 0L) return null
            val part = lastUnit / d
            val after = t.subList(k + 1, t.size)
            if (after.isEmpty()) return value + part
            val factor = after.singleOrNull()?.takeIf { it != "k" }?.let { scaleFactor[it] } ?: return null
            return if (lastUnit <= 100 * PER && value < factor * PER) (value + part) * factor else null
        }
    }
}
