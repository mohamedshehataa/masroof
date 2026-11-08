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

    /** The first due date after the latest statement date that is on or after [today] (a due date already past is not "next"). */
    fun nextDue(
        today: LocalDate,
        statementDay: Int,
        dueDay: Int,
    ): LocalDate {
        val statement = lastStatement(today, statementDay)
        var month = YearMonth.from(statement)
        var due = clamp(dueDay, month)
        while (due <= statement || due < today) {
            month = month.plusMonths(1)
            due = clamp(dueDay, month)
        }
        return due
    }
}
