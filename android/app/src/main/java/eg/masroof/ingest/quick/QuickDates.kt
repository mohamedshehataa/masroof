package eg.masroof.ingest.quick

import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A word of the line: [raw] as typed (digits normalised, lower case), [key] folded for matching. */
internal class Tok(
    val raw: String,
    val key: String,
)

/** A date found in the line and the tokens it used up. */
internal class DateHit(
    val date: LocalDate,
    val used: Set<Int>,
    val yearUsed: Boolean = false,
    /** False when the words were a date but not a usable one (31 Feb, ahead of today): the entry keeps today. */
    val stated: Boolean = true,
)

/** "today", "yesterday", "on 3 oct", "last friday" and their Arabic forms. [today] is already the Cairo day. */
internal object QuickDates {
    private const val MAX_DAY_DIGITS = 2
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private const val MAX_DAY = 31
    private const val YEAR_DIGITS = 4
    private const val DAYS_BEFORE_YESTERDAY = 2L

    fun find(
        tokens: List<Tok>,
        today: LocalDate,
        allowYear: Boolean = true,
    ): DateHit? {
        for (i in tokens.indices) {
            val key = tokens[i].key
            val hit =
                when {
                    ISO_DATE.matches(key) -> isoDate(key, i, today)
                    key in QuickLexicon.YESTERDAY -> beforeYesterday(tokens, i, today)
                    key in QuickLexicon.TODAY -> DateHit(today, setOf(i))
                    key == "last" -> lastWeekday(tokens, i, today)
                    key in QuickLexicon.MONTHS -> monthDate(tokens, i, today, allowYear)
                    else -> null
                }
            if (hit != null) return hit
        }
        return null
    }

    // 2026-10-03. A day that does not exist, or one still ahead of today, is still a date and not an amount: the entry keeps today.
    private fun isoDate(
        key: String,
        i: Int,
        today: LocalDate,
    ): DateHit {
        val date =
            try {
                LocalDate.parse(key).takeUnless { it.isAfter(today) }
            } catch (_: java.time.format.DateTimeParseException) {
                null
            }
        return DateHit(date ?: today, setOf(i), stated = date != null)
    }

    // "أول امبارح" is the day before yesterday.
    private fun beforeYesterday(
        tokens: List<Tok>,
        i: Int,
        today: LocalDate,
    ): DateHit =
        if (i > 0 && tokens[i - 1].key == "اول") {
            DateHit(today.minusDays(DAYS_BEFORE_YESTERDAY), setOf(i - 1, i))
        } else {
            DateHit(today.minusDays(1), setOf(i))
        }

    private fun lastWeekday(
        tokens: List<Tok>,
        i: Int,
        today: LocalDate,
    ): DateHit? {
        val day = tokens.getOrNull(i + 1)?.let { QuickLexicon.WEEKDAYS[it.key] } ?: return null
        return DateHit(today.with(TemporalAdjusters.previous(day)), setOf(i, i + 1))
    }

    private fun dayNumber(tok: Tok?): Int? =
        tok
            ?.key
            ?.takeIf { it.length <= MAX_DAY_DIGITS && it.all(Char::isDigit) }
            ?.toInt()
            ?.takeIf { it in 1..MAX_DAY }

    private fun monthDate(
        tokens: List<Tok>,
        i: Int,
        today: LocalDate,
        allowYear: Boolean,
    ): DateHit? {
        val month = QuickLexicon.MONTHS.getValue(tokens[i].key)
        val before = dayNumber(tokens.getOrNull(i - 1))
        val after = dayNumber(tokens.getOrNull(i + 1))
        val dayIndex = if (before != null) i - 1 else i + 1
        val day = before ?: after ?: return null
        val used = mutableSetOf(i, dayIndex)
        val yearIndex = if (before != null) i + 1 else i + 2
        // A 4-digit number is a year only when it is this year or last, is not followed by a currency word (then it is the
        // amount) and does not make the date a future one. "3 oct 1500 pounds" is 1500 pounds, never the year 1500.
        val year =
            tokens
                .getOrNull(yearIndex)
                ?.key
                ?.takeIf { allowYear && it.length == YEAR_DIGITS && it.all(Char::isDigit) }
                ?.toInt()
                ?.takeIf { it in (today.year - 1)..today.year }
                ?.takeIf { QuickLexicon.CURRENCIES[tokens.getOrNull(yearIndex + 1)?.key] == null }
        val date =
            try {
                val dated = year?.let { LocalDate.of(it, month, day) }?.takeUnless { it.isAfter(today) }
                dated?.let { used += yearIndex }
                val candidate = dated ?: LocalDate.of(today.year, month, day)
                // No usable year: a date still ahead of today means last year (an expense is never in the future).
                if (dated == null && candidate.isAfter(today)) candidate.minusYears(1) else candidate
            } catch (_: DateTimeException) {
                // "31 feb": not a day that exists. The words are still a date, not an amount; the entry keeps today.
                return DateHit(today, used, stated = false)
            }
        return DateHit(date, used, yearUsed = yearIndex in used)
    }
}
