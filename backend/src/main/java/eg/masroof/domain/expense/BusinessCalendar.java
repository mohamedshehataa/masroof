package eg.masroof.domain.expense;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Business dates are Africa/Cairo calendar days; instants are stored in UTC. */
public final class BusinessCalendar {

  /** The only supported household timezone. */
  public static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

  private BusinessCalendar() {}

  /** The Cairo calendar date of an instant. */
  public static LocalDate dateOf(Instant instant) {
    return instant.atZone(CAIRO).toLocalDate();
  }

  /** The first instant of the Cairo day (inclusive range start). */
  public static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(CAIRO).toInstant();
  }

  /** The first instant after the Cairo day (exclusive range end), correct across DST changes. */
  public static Instant endOfDayExclusive(LocalDate date) {
    return date.plusDays(1).atStartOfDay(CAIRO).toInstant();
  }
}
