package eg.masroof.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** All business dates are computed in Africa/Cairo and stored as UTC instants (epoch millis in Room). */
object CairoTime {
    val zone: ZoneId = ZoneId.of("Africa/Cairo")

    fun today(now: Instant): LocalDate = now.atZone(zone).toLocalDate()

    /** Start (inclusive) and end (exclusive) of the calendar month containing [now], in epoch millis. */
    fun monthRangeMillis(now: Instant): Pair<Long, Long> {
        val first = today(now).withDayOfMonth(1)
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end =
            first
                .plusMonths(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        return start to end
    }

    /** Noon Cairo time on [date]: keeps the calendar day stable across DST shifts and UTC conversion. */
    fun noonOf(date: LocalDate): Instant = date.atTime(12, 0).atZone(zone).toInstant()

    fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
