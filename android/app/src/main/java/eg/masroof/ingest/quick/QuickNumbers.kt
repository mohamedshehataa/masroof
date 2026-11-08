package eg.masroof.ingest.quick

import eg.masroof.core.MoneyFormat
import eg.masroof.ingest.quick.QuickLexicon.HUNDRED
import eg.masroof.ingest.quick.QuickLexicon.TEN
import eg.masroof.ingest.quick.QuickLexicon.UNIT

/** One word of a spoken or typed number. */
internal sealed interface Piece {
    /** Typed digits, already in minor units. [bad] = more than two decimals ("1.234"), which is not an amount. */
    class Digits(
        val minor: Long,
        val bad: Boolean,
    ) : Piece

    class Word(
        val word: QuickLexicon.NumWord,
        /** The spoken "تلت": 3 as a count ("تلت آلاف"), a third after "إلا" ("ألف إلا تلت"). */
        val third: Boolean = false,
    ) : Piece

    /** "إلا": the fraction that follows is taken away ("ألف إلا ربع" = 750). */
    data object Except : Piece

    class Scale(
        val scale: QuickLexicon.ScaleWord,
        /** May open a number on its own ("ألف جنيه"); "k" and "آلاف" need a count first. */
        val starter: Boolean,
    ) : Piece

    /** A half (divisor 2) or a quarter (4). [joined]: written with the "و" glued on ("ونص"). */
    class Fraction(
        val divisor: Long,
        val joined: Boolean,
    ) : Piece

    /** A word that looks like a scale ("تلافات", "ملايين"...) but is not understood: the number is then not an amount. */
    data object Unknown : Piece

    /** A currency word. It is part of a run only between a number and a fraction ("خمسين جنيه ونص"). */
    data object Cur : Piece

    /** The Arabic "و" that joins number words. */
    data object Conn : Piece
}

/** A stretch of tokens that makes up one number, [start]..[end] inclusive. */
internal class NumberRun(
    val start: Int,
    val end: Int,
    val pieces: List<Piece>,
)

/** The value of a [NumberRun]: a good amount, a phrase that is not a number, or one that is zero / over the limit. */
internal sealed interface RunValue {
    class Ok(
        val minor: Long,
    ) : RunValue

    data object Bad : RunValue

    data object OutOfRange : RunValue
}

/** Reads number words and digits into minor units with integer arithmetic only (never a floating point type). */
internal object QuickNumbers {
    private const val MINOR_PER_MAJOR = 100L
    private const val ALL_SLOTS = HUNDRED or TEN or UNIT
    private const val HALF_DIVISOR = 2L
    private const val QUARTER_DIVISOR = 4L
    private const val THIRD_DIVISOR = 3L
    private const val HUNDRED_MAJOR = 100L
    private const val HUNDRED_MINOR = HUNDRED_MAJOR * MINOR_PER_MAJOR
    private val ZERO_TOKEN = Regex("""0+([.,]0+)?""")

    // Only these digit shapes are amounts: 150, 85.5, 1,250.75, 1,250, 12,5. Anything else ("1,2345", "150.5.5", "-5") is not.
    private val VALID_DIGITS = Regex("""\d+(\.\d{1,2})?|\d{1,3}(,\d{3})+(\.\d{1,2})?|\d+,\d{1,2}""")

    // Scale-like words that are not in the table: reading just the number before them would be 1000x too small.
    private val SCALE_LOOKALIKE = Regex("""[تث]لا?ف(ات|ا)?|الاف(ات)?|الفا|الفات|مل[يا][اوي]ن\p{L}*|ملايين\p{L}*""")

    fun classify(tok: Tok): Piece? {
        if (tok.key.first().isDigit() || tok.key.first() == '-') return digits(tok.key)
        direct(tok.key)?.let { return it }
        // "وخمسين": the joining "و" is written onto the next word.
        if (tok.key.length > 2 && tok.key.startsWith("و")) {
            direct(tok.key.substring(1))?.let { return if (it is Piece.Fraction) Piece.Fraction(it.divisor, true) else it }
        }
        return when {
            tok.key == "و" -> Piece.Conn
            tok.key in QuickLexicon.CURRENCIES -> Piece.Cur
            SCALE_LOOKALIKE.matches(tok.key.removePrefix("و")) -> Piece.Unknown
            else -> null
        }
    }

    private fun direct(key: String): Piece? =
        when {
            key in QuickLexicon.HALF -> Piece.Fraction(HALF_DIVISOR, false)
            key in QuickLexicon.QUARTER -> Piece.Fraction(QUARTER_DIVISOR, false)
            key == "الا" -> Piece.Except
            else ->
                QuickLexicon.NUMBER_WORDS[key]?.let { Piece.Word(it, key == "تلت") }
                    ?: QuickLexicon.SCALE_WORDS[key]?.let { Piece.Scale(it, key in QuickLexicon.SCALE_STARTERS) }
        }

    private fun digits(token: String): Piece {
        if (!VALID_DIGITS.matches(token)) return Piece.Digits(0L, true)
        MoneyFormat.parseMinor(token)?.let { return Piece.Digits(it, false) }
        // A well-formed number that is zero or does not fit a Long.
        return Piece.Digits(if (ZERO_TOKEN.matches(token)) 0L else Long.MAX_VALUE, false)
    }

    /** Groups tokens into runs; [pieces] holds null for a token that is not part of any number (or was used as a date). */
    fun runs(pieces: List<Piece?>): List<NumberRun> {
        val runs = mutableListOf<NumberRun>()
        var i = 0
        while (i < pieces.size) {
            val first = pieces[i]
            if (first == null || !starts(first)) {
                i++
                continue
            }
            val list = mutableListOf(first)
            val end = extend(pieces, list, i + 1)
            runs += NumberRun(i, end - 1, list)
            i = end
        }
        return runs
    }

    /** Adds the pieces that continue the run from index [from] to [list]; returns the index after the last one used. */
    private fun extend(
        pieces: List<Piece?>,
        list: MutableList<Piece>,
        from: Int,
    ): Int {
        var j = from
        while (j < pieces.size) {
            val q = pieces[j]
            // Several "و" in a row act as one.
            var m = j
            while (pieces.getOrNull(m) is Piece.Conn) m++
            val next = pieces.getOrNull(m)
            val bridgeTo = if (q is Piece.Cur && list.last() !is Piece.Cur) fractionAfter(pieces, j + 1) else null
            if (bridgeTo != null) {
                // "خمسين جنيه ونص": the currency word sits between the number and its half.
                list += Piece.Cur
                repeat(bridgeTo - j - 1) { list += Piece.Conn }
                list += pieces[bridgeTo] as Piece
                j = bridgeTo + 1
            } else if (q is Piece.Conn && next != null && continues(next, list.last(), true, list.size == 1)) {
                repeat(m - j) { list += Piece.Conn }
                list += next
                j = m + 1
            } else if (q != null && q !is Piece.Conn && continues(q, list.last(), false, list.size == 1)) {
                list += q
                j++
            } else {
                break
            }
        }
        return j
    }

    /** Index of the fraction word (or "إلا") at or after [from], skipping "و", or null when something else comes first. */
    private fun fractionAfter(
        pieces: List<Piece?>,
        from: Int,
    ): Int? {
        var f = from
        while (pieces.getOrNull(f) is Piece.Conn) f++
        return f.takeIf { pieces.getOrNull(f).let { it is Piece.Fraction || it is Piece.Except } }
    }

    // A fraction word always belongs to a run, so a number next to one is judged as a whole (never read without it). "إلا" does
    // not open a run: on its own it is the word "only" ("مدفعتش إلا 50"); after a number it belongs to that number.
    private fun starts(p: Piece): Boolean =
        when (p) {
            is Piece.Digits, is Piece.Word, is Piece.Fraction -> true
            // "k" and "ك" never open a number; a plural ("تلاف") does, so it is judged with what follows and never dropped.
            is Piece.Scale -> p.starter || p.scale.times == 0
            else -> false
        }

    private fun continues(
        q: Piece,
        last: Piece,
        afterConn: Boolean,
        runStart: Boolean,
    ): Boolean =
        when (q) {
            // Digits straight after a fraction word that opens the run join it, so "نص 500" is judged as a whole (not an amount).
            is Piece.Digits -> afterConn || (runStart && last is Piece.Fraction)
            is Piece.Word -> afterConn || last !is Piece.Digits
            is Piece.Conn, is Piece.Cur -> false
            else -> true
        }

    private class State {
        var total = 0L
        var current = 0L
        var slots = 0
        var lastUnit = 0L
        var lastFactor = Long.MAX_VALUE

        /** The piece just before was typed digits (the only thing a "k" may follow). */
        var afterDigits = false
    }

    /** A number with no fraction words: its value and the size of the last unit spoken (a scale, a hundred, or 1). */
    private class Plain(
        val value: Long,
        val lastUnit: Long,
    )

    private fun compute(run: List<Piece>): RunValue {
        val pieces = run.filter { it !is Piece.Cur }
        val fractional = pieces.any { it is Piece.Fraction || it is Piece.Except || (it is Piece.Word && it.third) }
        val value = (if (fractional) fractionValue(pieces) else plain(pieces)?.value) ?: return RunValue.Bad
        return if (value <= 0 || value > MoneyFormat.MAX_ENTRY_MINOR) RunValue.OutOfRange else RunValue.Ok(value)
    }

    private fun plain(pieces: List<Piece>): Plain? {
        val s = State()
        for (p in pieces) {
            val ok =
                when (p) {
                    is Piece.Conn -> true
                    is Piece.Digits -> addDigits(s, p)
                    is Piece.Word -> addWord(s, p.word)
                    is Piece.Scale -> (!p.scale.abbreviation || s.afterDigits) && addScale(s, p.scale)
                    is Piece.Unknown, is Piece.Fraction, is Piece.Except, is Piece.Cur -> false
                }
            if (!ok) return null
            if (p !is Piece.Conn) s.afterDigits = p is Piece.Digits
        }
        return Plain(Math.addExact(s.total, s.current), s.lastUnit)
    }

    // The fraction grammar is a whitelist; anything outside it is not an amount (a missing amount is fine, a wrong one is not):
    //   <number> و<half|quarter>              "ألفين ونص" = 2,500   (half of the last unit)
    //   <half|quarter|third> <scale|مية>      "نص مليون" = 500,000  (third only when it divides exactly)
    //   <N> و<half|quarter> <scale>           "مية ونص ألف" = 150,000   (N below the scale, last unit a hundred or smaller)
    //   <scale|مية> إلا <half|quarter|third>  "ألف إلا ربع" = 750
    // A "تلت" that is not one of these is the count 3 only straight before a plural scale ("تلت آلاف").
    private fun fractionValue(pieces: List<Piece>): Long? {
        val fractions = pieces.indices.filter { pieces[it] is Piece.Fraction }
        val thirds = pieces.indices.filter { (pieces[it] as? Piece.Word)?.third == true }
        val plainThirds =
            thirds.all { i -> (pieces.getOrNull(i + 1) as? Piece.Scale)?.scale?.times == 0 }
        return when {
            fractions.isEmpty() && pieces.none { it is Piece.Except } && plainThirds -> plain(pieces)?.value
            pieces.size == 2 && divisorOf(pieces[0]) != null -> ofUnit(pieces[0], pieces[1])
            pieces.size == 3 && pieces[1] is Piece.Except -> exceptUnit(pieces)
            fractions.size == 1 && thirds.isEmpty() && pieces.none { it is Piece.Except } -> afterNumber(pieces, fractions[0])
            else -> null
        }
    }

    private fun divisorOf(p: Piece): Long? =
        when {
            p is Piece.Fraction -> p.divisor
            p is Piece.Word && p.third -> THIRD_DIVISOR
            else -> null
        }

    /** Whole units of a scale word ("ألف", "مليون") or of "مية"; nothing else. */
    private fun unitOf(p: Piece): Long? =
        when {
            p is Piece.Scale && p.scale.times == 1 && !p.scale.abbreviation -> p.scale.factor
            p is Piece.Word && !p.third && p.word.cls == HUNDRED && p.word.major == HUNDRED_MAJOR -> HUNDRED_MAJOR
            else -> null
        }

    private fun ofUnit(
        fraction: Piece,
        unit: Piece,
    ): Long? {
        val divisor = divisorOf(fraction) ?: return null
        val base = unitOf(unit)?.let { Math.multiplyExact(it, MINOR_PER_MAJOR) } ?: return null
        return if (base % divisor == 0L) base / divisor else null
    }

    private fun exceptUnit(p: List<Piece>): Long? {
        if (p[1] !is Piece.Except) return null
        // "ألف إلا ونص": a "و" after "إلا" is not the grammar.
        if ((p[2] as? Piece.Fraction)?.joined == true) return null
        val divisor = divisorOf(p[2]) ?: return null
        val base = unitOf(p[0])?.let { Math.multiplyExact(it, MINOR_PER_MAJOR) } ?: return null
        return if (base % divisor == 0L) base - base / divisor else null
    }

    private fun afterNumber(
        pieces: List<Piece>,
        at: Int,
    ): Long? {
        val fraction = pieces[at] as Piece.Fraction
        val before = pieces.subList(0, at)
        val prefix = before.dropLastWhile { it is Piece.Conn }
        // "ألفين ونص": the "و" is required, so "خمسين نص كيلو" is not 50.5.
        if (prefix.isEmpty() || !(fraction.joined || prefix.size < before.size)) return null
        val number = plain(prefix)?.takeIf { it.value > 0 && it.lastUnit % fraction.divisor == 0L } ?: return null
        val part = number.lastUnit / fraction.divisor
        val after = pieces.subList(at + 1, pieces.size)
        if (after.isEmpty()) return Math.addExact(number.value, part)
        val scale = (after.singleOrNull() as? Piece.Scale)?.scale?.takeUnless { it.abbreviation } ?: return null
        val fits =
            scale.times <= 1 &&
                number.lastUnit <= HUNDRED_MINOR &&
                number.value < Math.multiplyExact(scale.factor, MINOR_PER_MAJOR)
        return if (fits) Math.multiplyExact(Math.addExact(number.value, part), scale.factor) else null
    }

    fun evaluate(pieces: List<Piece>): RunValue =
        try {
            compute(pieces)
        } catch (_: ArithmeticException) {
            RunValue.OutOfRange
        }

    private fun addDigits(
        s: State,
        p: Piece.Digits,
    ): Boolean {
        if (p.bad) return false
        s.current = Math.addExact(s.current, p.minor)
        s.slots = ALL_SLOTS
        s.lastUnit = MINOR_PER_MAJOR
        return true
    }

    private fun addWord(
        s: State,
        w: QuickLexicon.NumWord,
    ): Boolean {
        if (s.slots and w.cls != 0) return false
        s.slots = s.slots or w.cls
        s.current = Math.addExact(s.current, w.major * MINOR_PER_MAJOR)
        s.lastUnit = if (w.cls == HUNDRED) HUNDRED_MINOR else MINOR_PER_MAJOR
        return true
    }

    private fun addScale(
        s: State,
        scale: QuickLexicon.ScaleWord,
    ): Boolean {
        // Scales go down: "مليون وخمسمية ألف" is fine, "ألف ألف" is not a number.
        if (scale.factor >= s.lastFactor) return false
        s.lastFactor = scale.factor
        val base =
            when (scale.times) {
                0 -> if (s.slots == 0) return false else s.current
                1 -> if (s.slots == 0) MINOR_PER_MAJOR else s.current
                else -> if (s.slots == 0) HALF_DIVISOR * MINOR_PER_MAJOR else return false
            }
        s.total = Math.addExact(s.total, Math.multiplyExact(base, scale.factor))
        s.current = 0L
        s.slots = 0
        s.lastUnit = Math.multiplyExact(scale.factor, MINOR_PER_MAJOR)
        return true
    }
}
