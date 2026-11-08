package eg.masroof.data.wealth

import java.time.LocalDate
import java.time.YearMonth

/** Statement and due dates of a credit card. A day beyond the month length clamps to the month's last day. */
object CardCycle {
    fun clamp(
        day: Int,
        month: YearMonth,
    ): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    /** The latest statement date on or before [today]. */
    fun lastStatement(
        today: LocalDate,
        statementDay: Int,
    ): LocalDate {
        val thisMonth = clamp(statementDay, YearMonth.from(today))
        return if (thisMonth <= today) thisMonth else clamp(statementDay, YearMonth.from(today).minusMonths(1))
    }

    /** The first due date after the latest statement date. */
    fun nextDue(
        today: LocalDate,
        statementDay: Int,
        dueDay: Int,
    ): LocalDate {
        val statement = lastStatement(today, statementDay)
        val sameMonth = clamp(dueDay, YearMonth.from(statement))
        return if (sameMonth > statement) sameMonth else clamp(dueDay, YearMonth.from(statement).plusMonths(1))
    }
}
