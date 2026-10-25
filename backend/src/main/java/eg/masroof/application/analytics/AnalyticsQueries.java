package eg.masroof.application.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Port: read-side aggregates over a household's expenses. Spending rows are the household
 * base-currency, non-deleted, non-ignored, non-duplicate expenses of type EXPENSE (plus
 * CASH_WITHDRAWAL on request) with REFUND netted off; TRANSFER never counts. Dates and hours are
 * Africa/Cairo.
 */
public interface AnalyticsQueries {

  /** The household's month start day, 1..28. */
  int monthStartDay(UUID householdId);

  /** Spending per Cairo day and hour. */
  List<DayHour> dayHourTotals(
      UUID householdId, String currency, Instant from, Instant to, boolean includeCash);

  /**
   * Spending per Cairo day and category. Sub-categories roll up into their parent, unless {@code
   * parentId} is set: then only that category and its direct children, each by itself.
   */
  List<CategoryDay> categoryDayTotals(
      UUID householdId,
      String currency,
      Instant from,
      Instant to,
      boolean includeCash,
      @Nullable UUID parentId);

  /** Income per Cairo day. */
  List<DayTotal> incomeDayTotals(UUID householdId, String currency, Instant from, Instant to);

  /** Top merchants by spend, grouped by trimmed case-insensitive merchant text. */
  List<MerchantRow> merchants(
      UUID householdId, String currency, Instant from, Instant to, boolean includeCash, int limit);

  /** Expenses waiting for review in the range. */
  int pendingReviewCount(UUID householdId, Instant from, Instant to);

  /** Spend in one Cairo hour of one day. */
  record DayHour(LocalDate date, int hour, long total, int count) {}

  /** A category as shown in reports. */
  record CategoryRef(@Nullable UUID id, String nameEn, String nameAr, @Nullable String color) {}

  /** Spend of one category on one day. */
  record CategoryDay(CategoryRef category, LocalDate date, long total, int count) {}

  /** A day total. */
  record DayTotal(LocalDate date, long total) {}

  /** A merchant aggregate. */
  record MerchantRow(String name, long total, int visits, @Nullable UUID categoryId) {}
}
