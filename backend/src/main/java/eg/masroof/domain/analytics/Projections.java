package eg.masroof.domain.analytics;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.OptionalInt;

/** Pure integer arithmetic for analytics: basis-point changes and period-end projections. */
public final class Projections {

  private static final long BPS = 10_000L;

  private Projections() {}

  /** Round-half-away-from-zero division of {@code numerator} by a positive {@code divisor}. */
  static long divideRounded(long numerator, long divisor) {
    if (divisor <= 0) {
      throw new IllegalArgumentException("divisor must be positive");
    }
    long abs = Math.addExact(Math.multiplyExact(Math.abs(numerator), 2L), divisor);
    long q = abs / Math.multiplyExact(divisor, 2L);
    return numerator < 0 ? -q : q;
  }

  /** Change from {@code previous} to {@code current} in basis points; empty when previous is 0. */
  public static OptionalInt deltaBps(long current, long previous) {
    if (previous <= 0) {
      return OptionalInt.empty();
    }
    long diff = Math.subtractExact(current, previous);
    return OptionalInt.of(Math.toIntExact(divideRounded(Math.multiplyExact(diff, BPS), previous)));
  }

  /** {@code part} as basis points of {@code whole}; 0 when whole is not positive. */
  public static int shareBps(long part, long whole) {
    if (whole <= 0) {
      return 0;
    }
    return Math.toIntExact(divideRounded(Math.multiplyExact(part, BPS), whole));
  }

  /** {@code (income - spent) / income} in basis points; empty without income. */
  public static OptionalInt savingsRateBps(long income, long spent) {
    if (income <= 0) {
      return OptionalInt.empty();
    }
    long saved = Math.subtractExact(income, spent);
    return OptionalInt.of(Math.toIntExact(divideRounded(Math.multiplyExact(saved, BPS), income)));
  }

  /** Mean per elapsed day, rounded; 0 before the first day ends. */
  public static long dailyAverage(long total, int elapsedDays) {
    return elapsedDays <= 0 ? 0 : divideRounded(total, elapsedDays);
  }

  /** Linear projection of {@code total} after {@code elapsedDays} to {@code lengthDays}. */
  public static long linear(long total, int elapsedDays, int lengthDays) {
    if (elapsedDays <= 0) {
      return 0;
    }
    if (elapsedDays >= lengthDays) {
      return total;
    }
    return divideRounded(Math.multiplyExact(total, (long) lengthDays), elapsedDays);
  }

  /**
   * Weekday-aware projection: each remaining day is expected to cost the mean of the elapsed days
   * with the same weekday; a weekday not seen yet falls back to the overall daily mean.
   *
   * @param periodStart first day of the period
   * @param elapsedDaily spend per elapsed day, index 0 being the period start
   * @param lengthDays days in the whole period
   */
  public static long weekdayAware(LocalDate periodStart, long[] elapsedDaily, int lengthDays) {
    int elapsed = elapsedDaily.length;
    long total = 0;
    for (long d : elapsedDaily) {
      total = Math.addExact(total, d);
    }
    if (elapsed == 0) {
      return 0;
    }
    if (elapsed >= lengthDays) {
      return total;
    }
    long[] sum = new long[7];
    int[] seen = new int[7];
    for (int i = 0; i < elapsed; i++) {
      int dow = periodStart.plusDays(i).getDayOfWeek().getValue() - 1;
      sum[dow] = Math.addExact(sum[dow], elapsedDaily[i]);
      seen[dow]++;
    }
    int[] remaining = new int[7];
    for (int i = elapsed; i < lengthDays; i++) {
      remaining[periodStart.plusDays(i).getDayOfWeek().getValue() - 1]++;
    }
    long projected = total;
    for (DayOfWeek day : DayOfWeek.values()) {
      int k = day.getValue() - 1;
      if (remaining[k] == 0) {
        continue;
      }
      long add =
          seen[k] > 0
              ? divideRounded(Math.multiplyExact(sum[k], (long) remaining[k]), seen[k])
              : divideRounded(Math.multiplyExact(total, (long) remaining[k]), elapsed);
      projected = Math.addExact(projected, add);
    }
    return projected;
  }
}
