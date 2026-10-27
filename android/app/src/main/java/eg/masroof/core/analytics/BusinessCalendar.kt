package eg.masroof.core.analytics

import eg.masroof.core.CairoTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Business dates are Africa/Cairo calendar days; instants are UTC. Port of the server's BusinessCalendar. */
object BusinessCalendar {
    /** The only supported household timezone (shared with [CairoTime]). */
    val CAIRO: ZoneId = CairoTime.zone

    /** The Cairo calendar date of an instant. */
    fun dateOf(instant: Instant): LocalDate = instant.atZone(CAIRO).toLocalDate()

    /** The first instant of the Cairo day (inclusive range start). */
    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(CAIRO).toInstant()

    /** The first instant after the Cairo day (exclusive range end), correct across DST changes. */
    fun endOfDayExclusive(date: LocalDate): Instant = date.plusDays(1).atStartOfDay(CAIRO).toInstant()
}
