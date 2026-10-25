package eg.masroof.application.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eg.masroof.application.analytics.AnalyticsQueries.CategoryDay;
import eg.masroof.application.analytics.AnalyticsQueries.CategoryRef;
import eg.masroof.application.analytics.AnalyticsQueries.DayHour;
import eg.masroof.application.analytics.AnalyticsQueries.DayTotal;
import eg.masroof.application.analytics.AnalyticsQueries.MerchantRow;
import eg.masroof.application.analytics.AnalyticsReports.Heatmap;
import eg.masroof.application.analytics.AnalyticsReports.Summary;
import eg.masroof.application.analytics.AnalyticsService.InvalidAnalyticsRequestException;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.domain.money.Money;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-AN-001")
class AnalyticsServiceTest {

  private static final UUID HOUSEHOLD = UUID.randomUUID();
  private static final UUID FOOD_ID = UUID.randomUUID();
  private static final CategoryRef FOOD = new CategoryRef(FOOD_ID, "Food", "طعام", "#2E7D32");
  private static final CategoryRef CAR = new CategoryRef(UUID.randomUUID(), "Car", "سيارة", null);
  private static final CategoryRef NONE = new CategoryRef(null, "Uncategorised", "غير مصنف", null);

  /** Fixed at 2026-10-25 12:00 Cairo. */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

  private final List<CategoryDay> categoryRows = new ArrayList<>();
  private final List<DayHour> dayHourRows = new ArrayList<>();
  private final List<DayTotal> incomeRows = new ArrayList<>();
  private final List<MerchantRow> merchantRows = new ArrayList<>();
  private int startDay = 1;
  private int pending = 0;
  private @Nullable UUID lastParent;
  private boolean lastCash;

  private final AnalyticsQueries fake =
      new AnalyticsQueries() {
        @Override
        public int monthStartDay(UUID householdId) {
          return startDay;
        }

        @Override
        public List<DayHour> dayHourTotals(
            UUID h, String c, Instant from, Instant to, boolean cash) {
          lastCash = cash;
          return dayHourRows.stream().filter(r -> inRange(r.date(), from, to)).toList();
        }

        @Override
        public List<CategoryDay> categoryDayTotals(
            UUID h, String c, Instant from, Instant to, boolean cash, @Nullable UUID parent) {
          lastParent = parent;
          return categoryRows.stream().filter(r -> inRange(r.date(), from, to)).toList();
        }

        @Override
        public List<DayTotal> incomeDayTotals(UUID h, String c, Instant from, Instant to) {
          return incomeRows.stream().filter(r -> inRange(r.date(), from, to)).toList();
        }

        @Override
        public List<MerchantRow> merchants(
            UUID h, String c, Instant from, Instant to, boolean cash, int limit) {
          return merchantRows.stream().limit(limit).toList();
        }

        @Override
        public int pendingReviewCount(UUID h, Instant from, Instant to) {
          return pending;
        }
      };

  private final AnalyticsService service =
      new AnalyticsService(fake, () -> new CurrentHousehold.Household(HOUSEHOLD, "EGP"), CLOCK);

  private static boolean inRange(LocalDate d, Instant from, Instant to) {
    Instant at = d.atTime(12, 0).atZone(java.time.ZoneId.of("Africa/Cairo")).toInstant();
    return !at.isBefore(from) && at.isBefore(to);
  }

  private void spend(CategoryRef cat, LocalDate d, int hour, long amount, int count) {
    categoryRows.add(new CategoryDay(cat, d, amount, count));
    dayHourRows.add(new DayHour(d, hour, amount, count));
  }

  private static LocalDate oct(int day) {
    return LocalDate.of(2026, 10, day);
  }

  @Test
  @DisplayName("REQ-AN-001 summary: total, delta vs last month, average, projection, days left")
  void summary() {
    // 25 days elapsed in October; 1000.00 EGP spent on the 1st and 24th each.
    spend(FOOD, oct(1), 9, 100_000, 2);
    spend(CAR, oct(24), 18, 100_000, 1);
    // September: 1000.00 EGP.
    spend(FOOD, LocalDate.of(2026, 9, 10), 9, 100_000, 1);
    incomeRows.add(new DayTotal(oct(5), 500_000));
    pending = 3;

    Summary s = service.summary("current", null);

    assertThat(s.period().label()).isEqualTo("2026-10");
    assertThat(s.total()).isEqualTo(Money.of(200_000, "EGP"));
    assertThat(s.previousTotal()).isEqualTo(Money.of(100_000, "EGP"));
    assertThat(s.deltaBps().getAsInt()).isEqualTo(10_000);
    assertThat(s.dailyAverage()).isEqualTo(Money.of(8_000, "EGP"));
    assertThat(s.projectedTotal()).isEqualTo(Money.of(248_000, "EGP"));
    assertThat(s.projectedWeekdayAware().amountMinor()).isGreaterThanOrEqualTo(200_000);
    assertThat(s.income()).isEqualTo(Money.of(500_000, "EGP"));
    assertThat(s.daysLeft()).isEqualTo(6);
    assertThat(s.pendingReviewCount()).isEqualTo(3);
    assertThat(s.topCategories()).hasSize(2);
    assertThat(s.topCategories().get(0).shareBps()).isEqualTo(5000);
    assertThat(lastCash).isFalse();
  }

  @Test
  @DisplayName("REQ-AN-001 summary of a finished month projects to its own total, no days left")
  void summaryPastMonth() {
    spend(FOOD, LocalDate.of(2026, 9, 10), 9, 100_000, 1);
    Summary s = service.summary("2026-09", true);
    assertThat(s.projectedTotal()).isEqualTo(s.total());
    assertThat(s.projectedWeekdayAware()).isEqualTo(s.total());
    assertThat(s.daysLeft()).isZero();
    assertThat(s.deltaBps()).isEmpty();
    assertThat(lastCash).isTrue();
  }

  @Test
  @DisplayName("REQ-AN-001 summary of a future month is all zeros")
  void summaryFutureMonth() {
    Summary s = service.summary("2026-12", null);
    assertThat(s.total().amountMinor()).isZero();
    assertThat(s.projectedTotal().amountMinor()).isZero();
    assertThat(s.dailyAverage().amountMinor()).isZero();
    assertThat(s.daysLeft()).isEqualTo(31);
  }

  @Test
  @DisplayName("REQ-AN-001 start day 25 shifts the current period")
  void honoursMonthStartDay() {
    startDay = 25;
    Summary s = service.summary("current", null);
    assertThat(s.period().from()).isEqualTo(oct(25));
    assertThat(s.period().to()).isEqualTo(LocalDate.of(2026, 11, 24));
    assertThat(s.daysLeft()).isEqualTo(30);
  }

  @Test
  @DisplayName("REQ-AN-001 forecast mirrors the summary projections")
  void forecast() {
    spend(FOOD, oct(1), 9, 250_000, 1);
    var f = service.forecast("current");
    assertThat(f.spentSoFar().amountMinor()).isEqualTo(250_000);
    assertThat(f.linear().amountMinor()).isEqualTo(310_000);
    assertThat(f.weekdayAware().amountMinor()).isGreaterThanOrEqualTo(250_000);
  }

  @Test
  @DisplayName("REQ-AN-002 categories ranked with share and month-over-month change")
  void byCategory() {
    spend(FOOD, oct(2), 9, 60_000, 3);
    spend(CAR, oct(3), 9, 30_000, 1);
    spend(NONE, oct(4), 9, 10_000, 1);
    spend(FOOD, LocalDate.of(2026, 9, 2), 9, 40_000, 2);

    var r = service.byCategory("current", null, null);

    assertThat(r.total().amountMinor()).isEqualTo(100_000);
    assertThat(r.items())
        .extracting(l -> l.category().nameEn())
        .containsExactly("Food", "Car", "Uncategorised");
    assertThat(r.items().get(0).shareBps()).isEqualTo(6000);
    assertThat(r.items().get(0).deltaBps().getAsInt()).isEqualTo(5000);
    assertThat(r.items().get(0).previousTotal().amountMinor()).isEqualTo(40_000);
    assertThat(r.items().get(0).count()).isEqualTo(3);
    assertThat(r.items().get(1).deltaBps()).isEmpty();
    assertThat(r.items().get(2).category().id()).isNull();
  }

  @Test
  @DisplayName("REQ-AN-002 drill-down passes the parent id and ignores non-positive categories")
  void drillDownAndRefundOnlyCategory() {
    spend(FOOD, oct(2), 9, 60_000, 1);
    spend(CAR, oct(3), 9, -5_000, 1);
    var r = service.byCategory("current", null, FOOD_ID);
    assertThat(lastParent).isEqualTo(FOOD_ID);
    assertThat(r.items()).hasSize(1);
    assertThat(r.total().amountMinor()).isEqualTo(55_000);
  }

  @Test
  @DisplayName("REQ-AN-005 trend: oldest first, per-period totals, income and savings rate")
  void trend() {
    spend(FOOD, LocalDate.of(2026, 8, 10), 9, 100_000, 1);
    spend(FOOD, LocalDate.of(2026, 10, 10), 9, 80_000, 1);
    incomeRows.add(new DayTotal(LocalDate.of(2026, 10, 1), 400_000));

    var points = service.trend(3, null);

    assertThat(points)
        .extracting(p -> p.period().label())
        .containsExactly("2026-08", "2026-09", "2026-10");
    assertThat(points.get(0).total().amountMinor()).isEqualTo(100_000);
    assertThat(points.get(0).savingsRateBps()).isEmpty();
    assertThat(points.get(1).total().amountMinor()).isZero();
    assertThat(points.get(1).byCategory()).isEmpty();
    assertThat(points.get(2).income().amountMinor()).isEqualTo(400_000);
    assertThat(points.get(2).savingsRateBps().getAsInt()).isEqualTo(8000);
    assertThat(points.get(2).byCategory()).hasSize(1);
  }

  @Test
  @DisplayName("REQ-AN-005 trend rejects months outside 1..36")
  void trendBounds() {
    assertThatThrownBy(() -> service.trend(0, null))
        .isInstanceOfSatisfying(
            InvalidAnalyticsRequestException.class, e -> assertThat(e.field()).isEqualTo("months"));
    assertThatThrownBy(() -> service.trend(37, null))
        .isInstanceOf(InvalidAnalyticsRequestException.class);
    assertThat(service.trend(36, null)).hasSize(36);
  }

  @Test
  @DisplayName("REQ-AN-006 heatmap: zero-filled days and Saturday-first weekday x hour matrix")
  void heatmap() {
    // 2026-10-03 is a Saturday; 2026-10-04 a Sunday.
    assertThat(oct(3).getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
    spend(FOOD, oct(3), 14, 5_000, 2);
    spend(FOOD, oct(3), 14, 1_000, 1);
    spend(CAR, oct(4), 23, 7_000, 1);

    Heatmap h = service.heatmap("2026-10", null);

    assertThat(h.days()).hasSize(31);
    assertThat(h.days().get(2).date()).isEqualTo(oct(3));
    assertThat(h.days().get(2).total().amountMinor()).isEqualTo(6_000);
    assertThat(h.days().get(2).count()).isEqualTo(3);
    assertThat(h.days().get(0).total().amountMinor()).isZero();
    assertThat(h.weekdayHour()).hasSize(7);
    assertThat(h.weekdayHour().get(0)).hasSize(24);
    assertThat(h.weekdayHour().get(0).get(14).amountMinor()).isEqualTo(6_000);
    assertThat(h.weekdayHour().get(1).get(23).amountMinor()).isEqualTo(7_000);
    assertThat(h.weekdayHour().get(6).get(0).amountMinor()).isZero();
  }

  @Test
  @DisplayName("REQ-AN-006 weekday index puts Saturday first and Friday last")
  void weekdayIndex() {
    assertThat(AnalyticsService.weekdayIndex(DayOfWeek.SATURDAY)).isZero();
    assertThat(AnalyticsService.weekdayIndex(DayOfWeek.SUNDAY)).isEqualTo(1);
    assertThat(AnalyticsService.weekdayIndex(DayOfWeek.MONDAY)).isEqualTo(2);
    assertThat(AnalyticsService.weekdayIndex(DayOfWeek.FRIDAY)).isEqualTo(6);
  }

  @Test
  @DisplayName("REQ-AN-004 merchants: average ticket and limit validation")
  void merchants() {
    merchantRows.add(new MerchantRow("Spinneys", 30_000, 3, FOOD_ID));
    merchantRows.add(new MerchantRow("Uber", 10_000, 4, null));
    var r = service.merchants("current", 1);
    assertThat(r.items()).hasSize(1);
    assertThat(r.items().get(0).averageTicket().amountMinor()).isEqualTo(10_000);
    assertThat(service.merchants("current", 50).items().get(1).averageTicket().amountMinor())
        .isEqualTo(2_500);
    assertThatThrownBy(() -> service.merchants("current", 0))
        .isInstanceOf(InvalidAnalyticsRequestException.class);
    assertThatThrownBy(() -> service.merchants("current", 201))
        .isInstanceOf(InvalidAnalyticsRequestException.class);
  }

  @Test
  @DisplayName("REQ-AN-001 a malformed period is reported against the period field")
  void invalidPeriod() {
    assertThatThrownBy(() -> service.summary("soon", null))
        .isInstanceOfSatisfying(
            InvalidAnalyticsRequestException.class, e -> assertThat(e.field()).isEqualTo("period"));
    assertThatThrownBy(() -> service.heatmap("2026-W99", null))
        .isInstanceOf(InvalidAnalyticsRequestException.class);
  }
}
