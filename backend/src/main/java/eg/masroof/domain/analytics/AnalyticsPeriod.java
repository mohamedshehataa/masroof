package eg.masroof.domain.analytics;

import eg.masroof.domain.expense.BusinessCalendar;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An analytics period in Cairo calendar days, both ends inclusive. A month period starts on the
 * household {@code month_start_day} of the calendar month it is named after (so {@code 2026-10}
 * with start day 25 runs 25 Oct to 24 Nov); a week period is an ISO week, Monday to Sunday.
 */
public record AnalyticsPeriod(String label, LocalDate from, LocalDate to, boolean week) {

  private static final Pattern SPEC = Pattern.compile("^(\\d{4})-(?:(\\d{2})|W(\\d{2}))$");

  /** Validates the bounds. */
  public AnalyticsPeriod {
    Objects.requireNonNull(label, "label");
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("period end before start");
    }
  }

  /**
   * Resolves {@code current}, {@code previous}, {@code YYYY-MM} or {@code YYYY-Www}.
   *
   * @throws IllegalArgumentException when the spec is malformed or names a month/week that does not
   *     exist
   */
  public static AnalyticsPeriod parse(String spec, LocalDate today, int monthStartDay) {
    requireStartDay(monthStartDay);
    if ("current".equals(spec)) {
      return currentMonth(today, monthStartDay);
    }
    if ("previous".equals(spec)) {
      return currentMonth(today, monthStartDay).previous();
    }
    Matcher m = SPEC.matcher(spec);
    if (!m.matches()) {
      throw new IllegalArgumentException("period must be current, previous, YYYY-MM or YYYY-Www");
    }
    int year = Integer.parseInt(m.group(1));
    if (m.group(2) != null) {
      int month = Integer.parseInt(m.group(2));
      if (month < 1 || month > 12) {
        throw new IllegalArgumentException("month must be 01..12");
      }
      return month(YearMonth.of(year, month), monthStartDay);
    }
    int weekNo = Integer.parseInt(m.group(3));
    if (weekNo < 1 || weekNo > 53) {
      throw new IllegalArgumentException("week must be 01..53");
    }
    LocalDate monday =
        LocalDate.of(year, 1, 4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, weekNo)
            .with(DayOfWeek.MONDAY);
    if (monday.get(IsoFields.WEEK_BASED_YEAR) != year) {
      throw new IllegalArgumentException("year has no such ISO week");
    }
    return week(monday);
  }

  /** The household month that contains {@code today}. */
  public static AnalyticsPeriod currentMonth(LocalDate today, int monthStartDay) {
    requireStartDay(monthStartDay);
    YearMonth ym = YearMonth.from(today);
    return month(today.getDayOfMonth() >= monthStartDay ? ym : ym.minusMonths(1), monthStartDay);
  }

  /** The household month named after {@code ym}. */
  public static AnalyticsPeriod month(YearMonth ym, int monthStartDay) {
    requireStartDay(monthStartDay);
    LocalDate from = ym.atDay(monthStartDay);
    LocalDate to = ym.plusMonths(1).atDay(monthStartDay).minusDays(1);
    return new AnalyticsPeriod(ym.toString(), from, to, false);
  }

  /** The ISO week that starts on {@code monday}. */
  public static AnalyticsPeriod week(LocalDate monday) {
    String label =
        String.format(
            "%04d-W%02d",
            monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    return new AnalyticsPeriod(label, monday, monday.plusDays(6), true);
  }

  private static void requireStartDay(int day) {
    if (day < 1 || day > 28) {
      throw new IllegalArgumentException("month start day must be 1..28");
    }
  }

  /** The period right before this one, of the same kind. */
  public AnalyticsPeriod previous() {
    if (week) {
      return week(from.minusDays(7));
    }
    return month(YearMonth.from(from).minusMonths(1), from.getDayOfMonth());
  }

  /** Number of Cairo calendar days in the period. */
  public int lengthDays() {
    return Math.toIntExact(ChronoUnit.DAYS.between(from, to)) + 1;
  }

  /** First instant of the period (inclusive). */
  public Instant start() {
    return BusinessCalendar.startOfDay(from);
  }

  /** First instant after the period (exclusive), correct across DST changes. */
  public Instant endExclusive() {
    return BusinessCalendar.endOfDayExclusive(to);
  }

  /** Days of the period that have started as of {@code today}, between 0 and the length. */
  public int elapsedDays(LocalDate today) {
    if (today.isBefore(from)) {
      return 0;
    }
    if (today.isAfter(to)) {
      return lengthDays();
    }
    return Math.toIntExact(ChronoUnit.DAYS.between(from, today)) + 1;
  }

  /** Days after {@code today} until the period ends; 0 once it is over. */
  public int daysLeft(LocalDate today) {
    return lengthDays() - elapsedDays(today);
  }
}
