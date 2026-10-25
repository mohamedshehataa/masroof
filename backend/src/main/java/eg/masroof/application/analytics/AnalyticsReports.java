package eg.masroof.application.analytics;

import eg.masroof.application.analytics.AnalyticsQueries.CategoryRef;
import eg.masroof.domain.analytics.AnalyticsPeriod;
import eg.masroof.domain.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Result types of {@link AnalyticsService}. */
public final class AnalyticsReports {

  private AnalyticsReports() {}

  /** One ranked category. */
  public record CategoryLine(
      CategoryRef category,
      Money total,
      Money previousTotal,
      int shareBps,
      int count,
      OptionalInt deltaBps) {}

  /** Period overview. */
  public record Summary(
      AnalyticsPeriod period,
      Money total,
      Money previousTotal,
      OptionalInt deltaBps,
      Money dailyAverage,
      Money projectedTotal,
      Money projectedWeekdayAware,
      Money income,
      List<CategoryLine> topCategories,
      int daysLeft,
      int pendingReviewCount) {}

  /** Categories ranked by spend. */
  public record CategoryReport(AnalyticsPeriod period, Money total, List<CategoryLine> items) {}

  /** One period of a trend. */
  public record TrendPoint(
      AnalyticsPeriod period,
      Money total,
      Money income,
      OptionalInt savingsRateBps,
      List<CategoryLine> byCategory) {}

  /** Spend per day plus the weekday x hour matrix. */
  public record Heatmap(
      AnalyticsPeriod period, List<DayCell> days, List<List<Money>> weekdayHour) {}

  /** A heatmap day. */
  public record DayCell(LocalDate date, Money total, int count) {}

  /** A merchant line. */
  public record MerchantLine(
      String name, Money total, int visits, Money averageTicket, @Nullable UUID categoryId) {}

  /** Top merchants. */
  public record MerchantReport(AnalyticsPeriod period, List<MerchantLine> items) {}

  /** Month-end projections. */
  public record Forecast(
      AnalyticsPeriod period, Money spentSoFar, Money linear, Money weekdayAware) {}
}
