package eg.masroof.ingest.quick

import eg.masroof.core.Money
import eg.masroof.ingest.parse.TextNormalizer
import java.time.LocalDate

enum class EntryType { EXPENSE, INCOME }

/**
 * What one spoken or typed line says (STORY-200). Every field is a suggestion for the Add form: nothing is saved from it.
 * [categoryKey] is the reference key of a seeded category (`car.fuel`, `groceries`), resolved to a row by the caller.
 */
data class EntryDraft(
    val amountMinor: Long?,
    val currency: String,
    val type: EntryType,
    val categoryKey: String?,
    val merchant: String?,
    val date: LocalDate,
    val note: String?,
    /** The line named a currency (otherwise [currency] is just the default and the form keeps its own choice). */
    val currencyStated: Boolean = false,
    /** The line named a day (otherwise [date] is just today and the form keeps its own date). */
    val dateStated: Boolean = false,
)

/**
 * Rules-only parser for "150 pounds on fuel", "دفعت مية وخمسين في كارفور امبارح" and mixed lines. Pure Kotlin, no Android types,
 * no network, no logging: the line is never kept or printed. Money is integer minor units only.
 */
object QuickEntryParser {
    private const val MAX_LINE = 400
    private const val MAX_NOTE = 200
    private const val MAX_MERCHANT_WORDS = 3
    private val TOKEN = Regex("""\d+(?:,\d{3})*(?:[.,]\d+)?|[${'$'}€£]|[\p{L}\p{M}]+""")
    private val LATIN_LE = Regex("""\bl\.e\b\.?""")

    fun parse(
        line: String,
        today: LocalDate,
    ): EntryDraft {
        val tokens = tokenize(line)
        val dateHit = QuickDates.find(tokens, today)
        val used = dateHit?.used ?: emptySet()
        val pieces = tokens.mapIndexed { i, t -> if (i in used) null else QuickNumbers.classify(t) }
        val runs = QuickNumbers.runs(pieces)
        val ctx = Ctx(tokens, used, runs)

        val chosen = runs.firstOrNull { ctx.currencyAt(it.start - 1) != null || ctx.currencyAt(it.end + 1) != null } ?: runs.lastOrNull()
        val value = chosen?.let { QuickNumbers.evaluate(it.pieces) }
        val stated =
            chosen?.let { ctx.currencyAt(it.end + 1) ?: ctx.currencyAt(it.start - 1) }
                ?: tokens.indices.firstNotNullOfOrNull { ctx.currencyAt(it) }
        val income = tokens.indices.any { it !in used && tokens[it].key in QuickLexicon.INCOME_WORDS }
        val type = if (income) EntryType.INCOME else EntryType.EXPENSE
        val note =
            when {
                value is RunValue.Bad -> line.trim().take(MAX_NOTE)
                runs.size > 1 && chosen != null -> ctx.leftover(chosen)
                else -> null
            }
        return EntryDraft(
            amountMinor = (value as? RunValue.Ok)?.minor,
            currency = stated ?: Money.EGP,
            type = type,
            categoryKey = if (type == EntryType.EXPENSE) ctx.category() else null,
            merchant = ctx.merchant(),
            date = dateHit?.date ?: today,
            note = note,
            currencyStated = stated != null,
            dateStated = dateHit != null,
        )
    }

    /** Digits to one set, Arabic marks removed, lower case; then words, numbers and currency symbols. */
    private fun tokenize(line: String): List<Tok> {
        val light = StringBuilder()
        for (ch in line.take(MAX_LINE)) {
            when (ch) {
                in '٠'..'٩' -> light.append('0' + (ch - '٠'))
                in '۰'..'۹' -> light.append('0' + (ch - '۰'))
                '٫' -> light.append('.')
                '٬', '،' -> light.append(',')
                in 'ً'..'ْ', 'ـ', '‎', '‏' -> Unit
                else -> light.append(ch.lowercaseChar())
            }
        }
        val text = light.toString().replace("ج.م", " جنيه ").replace(LATIN_LE, " le ")
        return TOKEN.findAll(text).map { Tok(it.value, TextNormalizer.fold(it.value)) }.toList()
    }

    private class Ctx(
        val tokens: List<Tok>,
        val used: Set<Int>,
        val runs: List<NumberRun>,
    ) {
        private val inRun =
            BooleanArray(tokens.size).also { flags ->
                for (r in runs) for (i in r.start..r.end) flags[i] = true
            }

        fun currencyAt(i: Int): String? =
            if (i in tokens.indices &&
                i !in used &&
                !inRun[i]
            ) {
                QuickLexicon.CURRENCIES[tokens[i].key]
            } else {
                null
            }

        /** Words that are not a number, a date, a currency or filler: the part of a two-number line that goes to the note. */
        fun leftover(chosen: NumberRun): String? =
            tokens.indices
                .filter { it !in used && it !in chosen.start..chosen.end && currencyAt(it) == null }
                .filter { tokens[it].key !in QuickLexicon.STOP_WORDS && tokens[it].key !in QuickLexicon.INCOME_WORDS }
                .joinToString(" ") { tokens[it].raw }
                .take(MAX_NOTE)
                .ifEmpty { null }

        private fun free(i: Int): Boolean {
            val key = tokens[i].key
            return i !in used && !inRun[i] && currencyAt(i) == null && key !in QuickLexicon.STOP_WORDS && key !in QuickLexicon.INCOME_WORDS
        }

        private fun keywordCategory(key: String): String? =
            QuickLexicon.CATEGORY_KEYWORDS[key] ?: QuickLexicon.CATEGORY_KEYWORDS[stripArticle(key)]

        private fun isMerchant(key: String) = key in QuickLexicon.MERCHANTS || stripArticle(key) in QuickLexicon.MERCHANTS

        // The Arabic definite article "ال" glued to a word: "المطعم" is "مطعم".
        private fun stripArticle(key: String) = if (key.startsWith("ال") && key.length > 3) key.substring(2) else key

        /** The most specific category any word points at (a subcategory beats its parent), else the first. */
        fun category(): String? {
            val keys = tokens.indices.filter { free(it) }.mapNotNull { keywordCategory(tokens[it].key) }
            return keys.firstOrNull { '.' in it } ?: keys.firstOrNull()
        }

        /** The name after "at" / "في", else the first known merchant. A plain category word ("fuel") is not a merchant. */
        fun merchant(): String? {
            for (i in tokens.indices) {
                if (i in used || tokens[i].key !in QuickLexicon.MERCHANT_PREPOSITIONS) continue
                named(i + 1)?.let { return it }
            }
            return tokens.indices.firstOrNull { free(it) && isMerchant(tokens[it].key) }?.let { tokens[it].raw }
        }

        private fun named(from: Int): String? {
            var j = from
            while (j < tokens.size && tokens[j].key == "the") j++
            val parts = mutableListOf<String>()
            while (j < tokens.size && parts.size < MAX_MERCHANT_WORDS && free(j)) {
                val key = tokens[j].key
                if (keywordCategory(key) != null && !isMerchant(key)) break
                parts += tokens[j].raw
                j++
            }
            return parts.joinToString(" ").ifEmpty { null }
        }
    }
}
