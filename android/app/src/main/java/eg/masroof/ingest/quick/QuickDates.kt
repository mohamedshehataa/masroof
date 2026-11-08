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
)

/** "today", "yesterday", "on 3 oct", "last friday" and their Arabic forms. [today] is already the Cairo day. */
internal object QuickDates {
    private const val MAX_DAY_DIGITS = 2
    private const val MAX_DAY = 31
    private const val YEAR_DIGITS = 4
    private const val DAYS_BEFORE_YESTERDAY = 2L

    fun find(
        tokens: List<Tok>,
        today: LocalDate,
    ): DateHit? {
        for (i in tokens.indices) {
            val key = tokens[i].key
            val hit =
                when {
                    key in QuickLexicon.YESTERDAY -> beforeYesterday(tokens, i, today)
                    key in QuickLexicon.TODAY -> DateHit(today, setOf(i))
                    key == "last" -> lastWeekday(tokens, i, today)
                    key in QuickLexicon.MONTHS -> monthDate(tokens, i, today)
                    else -> null
                }
            if (hit != null) return hit
        }
        return null
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
    ): DateHit? {
        val month = QuickLexicon.MONTHS.getValue(tokens[i].key)
        val before = dayNumber(tokens.getOrNull(i - 1))
        val after = dayNumber(tokens.getOrNull(i + 1))
        val dayIndex = if (before != null) i - 1 else i + 1
        val day = before ?: after ?: return null
        val used = mutableSetOf(i, dayIndex)
        var year: Int? = null
        val yearIndex = if (before != null) i + 1 else i + 2
        tokens.getOrNull(yearIndex)?.key?.takeIf { it.length == YEAR_DIGITS && it.all(Char::isDigit) }?.let {
            year = it.toInt()
            used += yearIndex
        }
        val date =
            try {
                val candidate = LocalDate.of(year ?: today.year, month, day)
                // No year given: a date still ahead of today means last year (an expense is never in the future).
                if (year == null && candidate.isAfter(today)) candidate.minusYears(1) else candidate
            } catch (_: DateTimeException) {
                // "31 feb": not a day that exists. The words are still a date, not an amount; the entry keeps today.
                return DateHit(today, used)
            }
        return DateHit(date, used)
    }
}
