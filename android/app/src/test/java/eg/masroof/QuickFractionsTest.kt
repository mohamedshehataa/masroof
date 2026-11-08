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
        assertNull(amount("إلا خمسة"))
        assertNull(amount("خمسة إلا"))
        assertNull(amount("نص و و خمسة"))
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

    /** A small independent reader of the intended grammar, working on the words (not on the parser's pieces). */
    private object Reference {
        private val ones = mapOf("واحد" to 1L, "اتنين" to 2L, "خمسة" to 5L, "تلت" to 3L)
        private val tens = mapOf("عشرة" to 10L, "عشرين" to 20L)
        private val hundreds = mapOf("مية" to 100L, "ميتين" to 200L, "خمسمية" to 500L)
        private val scaleFactor = mapOf("ألف" to 1_000L, "تلاف" to 1_000L, "مليون" to 1_000_000L)
        private val divisor = mapOf("نص" to 2L, "ربع" to 4L, "تلت" to 3L)
        private const val AND = "و"
        private const val EXCEPT = "إلا"
        private const val PER = 100L

        /** (value, size of the last unit) in piasters for a fraction-free number made of [words] (no "و"), or null. */
        @Suppress("CyclomaticComplexMethod")
        fun number(words: List<String>): Pair<Long, Long>? {
            var total = 0L
            var cur = 0L
            var seen = 0
            var lastUnit = 0L
            var lastScale = Long.MAX_VALUE
            for (w in words) {
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
            val t = raw.dropWhile { it == AND }.dropLastWhile { it == AND }
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
            val factor = after.singleOrNull()?.let { scaleFactor[it] } ?: return null
            return if (lastUnit <= 100 * PER && value < factor * PER) (value + part) * factor else null
        }
    }
}
