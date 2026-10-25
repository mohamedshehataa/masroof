package eg.masroof.ingest

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Secrecy no longer depends on recognising an OTP: every message is redacted on the device before it is queued or
 * uploaded. After normalization (NFKC, invisible characters and bidi marks removed, every digit script to ASCII,
 * unified dashes, dots and spaces) EVERY digit sequence is replaced by bullets, except what the parser needs:
 * (1) an amount directly next to a currency token, (2) dates and times, (3) the last four digits after a card or
 * account marker or a masked prefix. Digit groups separated by one space or dash count as one sequence.
 *
 * Mirrors the server's `SmsRedactor.java`; both are tested against `fixtures/sms/secrets.jsonl`. Idempotent.
 */
object SmsRedactor {
    private const val BULLET = '•'
    private const val MAX_LENGTH = 4000
    private const val MAX_PASSES = 6
    private const val MIN_YEAR = 1900
    private const val TWO_DIGIT_YEAR_BASE = 2000
    private const val LEAP_YEAR = "2024"
    private const val HOURS = 24
    private const val MINUTES = 60
    private const val CODE_LOOKBEHIND = 15
    private const val NUM = "\\d(?:[\\d.,]*\\d)?"
    private const val CUR =
        "(?:(?<![\\p{L}\\d])(?:egp|le|l\\.e\\.?|e£|usd|eur|sar|sr|aed|dhs|gbp|jpy|yen|tl|kwd|qar" +
            "|ج\\.?\\s?م\\.?|جنيه[اه]?|جنيهات|دولار|يورو|ريال|درهم" +
            "|(?-i:CHF|CAD|AUD|NZD|CNY|INR|TRY|RUB|KRW|SEK|NOK|DKK|PLN|CZK|HUF|ZAR|BRL|MXN|SGD|HKD" +
            "|THB|MYR|IDR|PHP|BHD|OMR|JOD|LBP|MAD|TND|DZD|ILS))(?![\\p{L}])|\\$|€|£)"

    private val ci = setOf(RegexOption.IGNORE_CASE)
    private val currencyThenNumber = Regex("($CUR)[ :]?($NUM)", ci)
    private val numberThenCurrency = Regex("(?<![\\d.,])($NUM) ?($CUR)", ci)
    private val keywordAmount = Regex("(?:amount(?: of)?|value|مبلغ|بمبلغ|قيمه|بقيمه)[ :]*($NUM)", ci)
    private val codeBefore = Regex("(?:code|otp|pin|key|token|passcode|password|رمز|كود|الرقم|سري)[^\\p{L}\\d]{0,3}$", ci)
    private val dateIso = Regex("(?<![\\d/.:-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d])")
    private val dateFull = Regex("(?<![\\d/.:-])(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d/:])")
    private val dateShort = Regex("(?<![\\d/.:-])(\\d{1,2})/(\\d{1,2})(?![\\d/:.])")
    private val time = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?![\\d:])")
    private val last4Masked = Regex("(?:[*x•]{2,}|\\.{3}) ?(\\d{4})(?!\\d)", ci)
    private val last4En =
        Regex("(?:ending(?: with| in)?|ends with|a/c|acct|account|card(?: no\\.?| number)?)[ :]*[*x•.-]* ?(\\d{4})(?!\\d)", ci)
    private val last4Ar =
        Regex("(?:المنتهيه|منتهيه|ينتهي|رقم|حساب|بطاق[هت][كم]*) ?(?:ب)? ?[*x•.]* ?(\\d{4})(?!\\d)", ci)

    /** Normalizes and redacts; the result is all that may be queued or uploaded. */
    fun redact(body: String): String = mask(normalizeText(body))

    /** Whether the normalized text has an amount next to a currency token or an amount keyword. */
    fun hasCurrencyAmount(normalized: String): Boolean {
        val f = fold(normalized)
        return currencyThenNumber.containsMatchIn(f) ||
            keywordAmount.containsMatchIn(f) ||
            protectAmountBeforeCurrency(f, BooleanArray(f.length))
    }

    /** Letter folding for matching only (same length): alef variants, final yaa, taa marbuta, Persian kaf. */
    internal fun fold(text: String): String =
        buildString(text.length) {
            for (c in text) {
                append(
                    when (c.code) {
                        0x0623, 0x0625, 0x0622, 0x0671 -> 'ا'
                        0x0649, 0x06CC -> 'ي'
                        0x0629 -> 'ه'
                        0x06A9 -> 'ك'
                        else -> c
                    },
                )
            }
        }

    private fun mask(text: String): String {
        val f = fold(text)
        val keep = BooleanArray(text.length)
        protectAmounts(f, keep)
        protectDatesAndTimes(f, keep)
        for (p in listOf(last4Masked, last4En, last4Ar)) {
            for (m in p.findAll(f)) protect(keep, m.groups[1]!!.range)
        }
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (!text[i].isDigit() || keep[i]) {
                out.append(text[i])
                i++
                continue
            }
            var end = i
            while (end < text.length) {
                end =
                    when {
                        text[end].isDigit() && !keep[end] -> end + 1
                        isGroupSeparator(text, end, keep) -> end + 1
                        else -> break
                    }
            }
            for (k in i until end) out.append(if (text[k].isDigit()) BULLET else text[k])
            i = end
        }
        return out.toString()
    }

    private fun isGroupSeparator(
        text: String,
        at: Int,
        keep: BooleanArray,
    ): Boolean =
        (text[at] == ' ' || text[at] == '-') &&
            !keep[at] &&
            at + 1 < text.length &&
            text[at + 1].isDigit() &&
            !keep[at + 1]

    private fun protectAmounts(
        f: String,
        keep: BooleanArray,
    ) {
        for (m in currencyThenNumber.findAll(f)) protect(keep, m.groups.last()!!.range)
        for (m in keywordAmount.findAll(f)) protect(keep, m.groups[1]!!.range)
        protectAmountBeforeCurrency(f, keep)
    }

    /** "500.00 EGP" / "1500 جنيه": the number is protected unless the currency already owns a number after it. */
    private fun protectAmountBeforeCurrency(
        f: String,
        keep: BooleanArray,
    ): Boolean {
        var found = false
        for (m in numberThenCurrency.findAll(f)) {
            val number = m.groups[1]!!.range
            val currency = m.groups[2]!!.range
            val ownsNext = Regex("(?s)^[ :]?\\d.*").matches(f.substring(currency.last + 1))
            val before = f.substring(maxOf(0, number.first - CODE_LOOKBEHIND), number.first)
            if (!ownsNext && !codeBefore.containsMatchIn(before)) {
                protect(keep, number)
                found = true
            }
        }
        return found
    }

    private fun protectDatesAndTimes(
        f: String,
        keep: BooleanArray,
    ) {
        for (m in dateIso.findAll(f)) {
            if (validDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])) protect(keep, m.range)
        }
        for (m in dateFull.findAll(f)) {
            if (validDate(m.groupValues[4], m.groupValues[3], m.groupValues[1])) protect(keep, m.range)
        }
        for (m in dateShort.findAll(f)) {
            if (validDate(LEAP_YEAR, m.groupValues[2], m.groupValues[1])) protect(keep, m.range)
        }
        for (m in time.findAll(f)) {
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val s = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h < HOURS && min < MINUTES && s < MINUTES) protect(keep, m.range)
        }
    }

    private fun validDate(
        year: String,
        month: String,
        day: String,
    ): Boolean =
        try {
            val y = year.toInt()
            LocalDate.of(if (year.length == 2) TWO_DIGIT_YEAR_BASE + y else y, month.toInt(), day.toInt())
            year.length == 2 || y >= MIN_YEAR
        } catch (_: DateTimeException) {
            false
        }

    private fun protect(
        keep: BooleanArray,
        range: IntRange,
    ) {
        for (i in range) keep[i] = true
    }

    /** NFKC, one ASCII digit set, no invisible or bidi characters, unified dashes/dots/spaces; idempotent, bounded. */
    fun normalizeText(input: String): String {
        var current = input
        repeat(MAX_PASSES) {
            val next = pass(current)
            if (next == current) return next
            current = next
        }
        return current
    }

    private fun pass(text: String): String {
        val folded = Normalizer.normalize(truncate(text), Normalizer.Form.NFKC)
        val out = StringBuilder(folded.length)
        var lastSpace = true
        for (c in folded) {
            val mapped = mapChar(c)
            if (mapped == DROP) continue
            if (mapped.isWhitespace() || Character.isSpaceChar(mapped)) {
                if (!lastSpace) out.append(' ')
                lastSpace = true
            } else {
                out.append(mapped)
                lastSpace = false
            }
        }
        if (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return truncate(Normalizer.normalize(out, Normalizer.Form.NFKC))
    }

    private fun truncate(text: String): String =
        if (text.codePointCount(0, text.length) <= MAX_LENGTH) text else text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH))

    private const val DROP = '\u0000'

    private fun mapChar(c: Char): Char {
        val code = c.code
        return when {
            code in 0x0660..0x0669 -> '0' + (code - 0x0660)
            code in 0x06F0..0x06F9 -> '0' + (code - 0x06F0)
            code == 0x066B -> '.'
            code == 0x066C || code == 0x060C -> ','
            code == 0x066A -> '%'
            code in DASHES || code == 0x2212 || code == 0xFE58 || code == 0xFE63 -> '-'
            code == 0x2024 || code == 0x2027 -> '.'
            code == 0x00A0 || code == 0x202F -> ' '
            isInvisible(code) -> DROP
            else -> c
        }
    }

    private val DASHES = 0x2010..0x2015

    private fun isInvisible(code: Int) =
        code == 0x0640 ||
            code == 0xFEFF ||
            code in 0x200B..0x200F ||
            code == 0x2060 ||
            code == 0x061C ||
            code in 0x064B..0x065F ||
            code == 0x0670 ||
            code in 0x06D6..0x06ED ||
            code in 0x202A..0x202E ||
            code in 0x2066..0x2069 ||
            code == 0x00AD ||
            code in 0x2061..0x2064 ||
            code == 0x034F ||
            code in 0xFE00..0xFE0F ||
            code == 0x180E
}
