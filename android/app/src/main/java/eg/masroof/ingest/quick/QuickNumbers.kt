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
    ) : Piece

    class Scale(
        val scale: QuickLexicon.ScaleWord,
        /** May open a number on its own ("ألف جنيه"); "k" and "آلاف" need a count first. */
        val starter: Boolean,
    ) : Piece

    data object Half : Piece

    data object Quarter : Piece

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
    private const val HUNDRED_MINOR = 100L * MINOR_PER_MAJOR
    private const val MAX_DECIMALS = 2
    private val ZERO_TOKEN = Regex("""0+([.,]0+)?""")

    fun classify(tok: Tok): Piece? {
        if (tok.key.first().isDigit()) return digits(tok.key)
        direct(tok.key)?.let { return it }
        // "وخمسين": the joining "و" is written onto the next word.
        if (tok.key.length > 2 && tok.key.startsWith("و")) return direct(tok.key.substring(1))
        return if (tok.key == "و") Piece.Conn else null
    }

    private fun direct(key: String): Piece? =
        when {
            key in QuickLexicon.HALF -> Piece.Half
            key in QuickLexicon.QUARTER -> Piece.Quarter
            else ->
                QuickLexicon.NUMBER_WORDS[key]?.let { Piece.Word(it) }
                    ?: QuickLexicon.SCALE_WORDS[key]?.let { Piece.Scale(it, key in QuickLexicon.SCALE_STARTERS) }
        }

    private fun digits(token: String): Piece {
        MoneyFormat.parseMinor(token)?.let { return Piece.Digits(it, false) }
        if (ZERO_TOKEN.matches(token)) return Piece.Digits(0L, false)
        val fraction = token.substringAfterLast('.', "").takeIf { '.' in token } ?: ""
        val tooPrecise = fraction.length > MAX_DECIMALS
        // Not parseable and not zero: either too many decimals, or too big for a Long.
        return if (tooPrecise) Piece.Digits(0L, true) else Piece.Digits(Long.MAX_VALUE, false)
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
            var last: Piece = first
            var j = i + 1
            while (j < pieces.size) {
                val q = pieces[j]
                val next = pieces.getOrNull(j + 1)
                if (q is Piece.Conn && next != null && continues(next, last, true)) {
                    list += q
                    list += next
                    last = next
                    j += 2
                } else if (q != null && q !is Piece.Conn && continues(q, last, false)) {
                    list += q
                    last = q
                    j++
                } else {
                    break
                }
            }
            runs += NumberRun(i, j - 1, list)
            i = j
        }
        return runs
    }

    private fun starts(p: Piece): Boolean =
        when (p) {
            is Piece.Digits, is Piece.Word -> true
            is Piece.Scale -> p.starter
            else -> false
        }

    private fun continues(
        q: Piece,
        last: Piece,
        afterConn: Boolean,
    ): Boolean =
        when (q) {
            is Piece.Digits -> afterConn || last is Piece.Scale
            is Piece.Word -> afterConn || last !is Piece.Digits
            is Piece.Conn -> false
            else -> true
        }

    fun evaluate(pieces: List<Piece>): RunValue =
        try {
            compute(pieces)
        } catch (_: ArithmeticException) {
            RunValue.OutOfRange
        }

    private class State {
        var total = 0L
        var current = 0L
        var slots = 0
        var lastUnit = 0L
        var lastFactor = Long.MAX_VALUE
        var halved = false
    }

    private fun compute(pieces: List<Piece>): RunValue {
        val s = State()
        for (p in pieces) {
            val ok =
                when (p) {
                    is Piece.Conn -> true
                    is Piece.Digits -> addDigits(s, p)
                    is Piece.Word -> addWord(s, p.word)
                    is Piece.Scale -> addScale(s, p.scale)
                    is Piece.Half -> addFraction(s, HALF_DIVISOR)
                    is Piece.Quarter -> addFraction(s, QUARTER_DIVISOR)
                }
            if (!ok) return RunValue.Bad
        }
        val value = Math.addExact(s.total, s.current)
        return if (value <= 0 || value > MoneyFormat.MAX_ENTRY_MINOR) RunValue.OutOfRange else RunValue.Ok(value)
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

    // "ألفين ونص" = 2,500: half (or a quarter) of the unit just spoken. Only once, and only after a number.
    private fun addFraction(
        s: State,
        divisor: Long,
    ): Boolean {
        if (s.lastUnit == 0L || s.halved) return false
        s.total = Math.addExact(s.total, s.lastUnit / divisor)
        s.halved = true
        return true
    }
}
