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
    /** The line said "paid"/"received"-style words, so the Expense/Income choice comes from the line. */
    val typeStated: Boolean = false,
)

/**
 * Rules-only parser for "150 pounds on fuel", "دفعت مية وخمسين في كارفور امبارح" and mixed lines. Pure Kotlin, no Android types,
 * no network, no logging: the line is never kept or printed. Money is integer minor units only.
 */
object QuickEntryParser {
    /** The merchant is capped like the manual field (the Add form uses the same constant). */
    const val MAX_MERCHANT_LENGTH = 80
    private const val MAX_LINE = 400
    private const val MAX_NOTE = 200
    private const val MAX_MERCHANT_WORDS = 3

    // Whole digit groups are one token so a malformed group ("1,2345", "150.5.5", "1k5", "-5") is rejected, not half read.
    private val TOKEN =
        Regex("""\d{4}-\d{2}-\d{2}|-?\d[\d.,]*[kK]\d[\d.,]*|-?\d[\d.,]*\d|-?\d|[${'$'}€£]|[\p{L}\p{M}]+""")
    private val POUND_E = Regex("""(?<!\p{L})e\s?£|£\s?e(?!\p{L})""")
    private val BIDI = Regex("[" + 0x202A.toChar() + "-" + 0x202E.toChar() + 0x2066.toChar() + "-" + 0x2069.toChar() + "]")
    private val LATIN_LE = Regex("""(?<!\p{L})l\.e(?!\p{L})\.?""")

    fun parse(
        line: String,
        today: LocalDate,
    ): EntryDraft {
        val tokens = tokenize(line)
        var dateHit = QuickDates.find(tokens, today, allowYear = true)
        var pieces = piecesOf(tokens, dateHit)
        var runs = QuickNumbers.runs(pieces)
        // A year only counts when another number is left to be the amount ("rent 5 nov 2026" is 2026 pounds).
        if (dateHit?.yearUsed == true && runs.isEmpty()) {
            dateHit = QuickDates.find(tokens, today, allowYear = false)
            pieces = piecesOf(tokens, dateHit)
            runs = QuickNumbers.runs(pieces)
        }
        val used = dateHit?.used ?: emptySet()
        val ctx = Ctx(tokens, used, runs)

        val chosen = runs.firstOrNull { ctx.currencyNear(it) } ?: runs.lastOrNull()
        // A currency we do not keep (dinar, yen ...) next to the amount: no amount, never silently pounds.
        val value = chosen?.let { if (ctx.foreignNear(it)) RunValue.Bad else QuickNumbers.evaluate(it.pieces) }
        val stated =
            chosen?.let { ctx.currencyAt(it.end + 1) ?: ctx.currencyAt(it.start - 1) }
                ?: tokens.indices.firstNotNullOfOrNull { ctx.currencyAt(it) }
        val spent = tokens.indices.any { it !in used && ctx.isExpenseVerb(it) }
        val earned = tokens.indices.any { it !in used && tokens[it].key in QuickLexicon.INCOME_WORDS }
        // "paid salary to nanny" is money going out: an expense verb beats an income word.
        val type = if (earned && !spent) EntryType.INCOME else EntryType.EXPENSE
        val dated = dateHit
        val note = noteOf(line, value, ctx, chosen)
        return EntryDraft(
            amountMinor = (value as? RunValue.Ok)?.minor,
            currency = stated ?: Money.EGP,
            type = type,
            categoryKey = if (type == EntryType.EXPENSE) ctx.category() else null,
            merchant = ctx.merchant()?.take(MAX_MERCHANT_LENGTH),
            date = dated?.date ?: today,
            note = note,
            currencyStated = stated != null,
            dateStated = dated?.stated == true,
            typeStated = earned || spent,
        )
    }

    // A phrase that is not a number keeps the whole line; two numbers keep the words that are not the amount.
    private fun noteOf(
        line: String,
        value: RunValue?,
        ctx: Ctx,
        chosen: NumberRun?,
    ): String? =
        when {
            value is RunValue.Bad -> line.replace(BIDI, "").trim().take(MAX_NOTE)
            ctx.runs.size > 1 && chosen != null -> ctx.leftover(chosen)
            else -> null
        }

    private fun piecesOf(
        tokens: List<Tok>,
        hit: DateHit?,
    ): List<Piece?> = tokens.mapIndexed { i, t -> if (hit != null && i in hit.used) null else QuickNumbers.classify(t) }

    /** Digits to one set, Arabic marks removed, lower case; then words, numbers and currency symbols. */
    private fun tokenize(line: String): List<Tok> {
        val light = StringBuilder()
        for (ch in line.take(MAX_LINE)) {
            when (ch) {
                in '٠'..'٩' -> light.append('0' + (ch - '٠'))
                in '۰'..'۹' -> light.append('0' + (ch - '۰'))
                '−' -> light.append('-')
                '٫' -> light.append('.')
                '٬', '،' -> light.append(',')
                in 'ً'..'ْ', 'ـ', '‎', '‏', in 0x202A.toChar()..0x202E.toChar(), in 0x2066.toChar()..0x2069.toChar() -> Unit
                else -> light.append(ch.lowercaseChar())
            }
        }
        val text =
            light
                .toString()
                .replace("ج.م", " جنيه ")
                .replace(LATIN_LE, " le ")
                .replace(POUND_E, " le ")
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

        fun currencyNear(r: NumberRun): Boolean =
            currencyAt(r.start - 1) != null || currencyAt(r.end + 1) != null || foreignNear(r)

        fun foreignNear(r: NumberRun): Boolean = foreignAt(r.start - 1) || foreignAt(r.end + 1)

        private fun foreignAt(i: Int): Boolean =
            i in tokens.indices && i !in used && !inRun[i] && tokens[i].key in QuickLexicon.UNSUPPORTED_CURRENCIES

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

        /** "paid", "spent", "دفعت" ... but not "got paid" (money coming in). */
        fun isExpenseVerb(i: Int): Boolean = tokens[i].key in QuickLexicon.EXPENSE_VERBS && tokens.getOrNull(i - 1)?.key != "got"

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
