package eg.masroof.application.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eg.masroof.application.analytics.AnalyticsQueries;
import eg.masroof.application.analytics.AnalyticsQueries.CategoryDay;
import eg.masroof.application.analytics.AnalyticsQueries.CategoryRef;
import eg.masroof.application.analytics.AnalyticsQueries.DayHour;
import eg.masroof.application.analytics.AnalyticsQueries.DayTotal;
import eg.masroof.application.analytics.AnalyticsQueries.MerchantRow;
import eg.masroof.application.budget.BudgetRepository.Budget;
import eg.masroof.application.budget.BudgetService.Command;
import eg.masroof.application.budget.BudgetService.Status;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.domain.analytics.AnalyticsPeriod;
import eg.masroof.domain.budget.BudgetPeriodKind;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-AN-008")
class BudgetServiceTest {

  private static final UUID HOUSEHOLD = UUID.randomUUID();
  private static final UUID FOOD = UUID.randomUUID();
  private static final CategoryRef FOOD_REF = new CategoryRef(FOOD, "Food", "طعام", null);
  private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

  /** 2026-10-25 12:00 Cairo (UTC+3). */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-25T09:00:00Z"), ZoneOffset.UTC);

  private final List<Budget> stored = new ArrayList<>();
  private final List<CategoryDay> categoryRows = new ArrayList<>();
  private final List<DayHour> dayRows = new ArrayList<>();
  private final List<Instant[]> ranges = new ArrayList<>();
  private int startDay = 1;

  private final BudgetRepository repo =
      new BudgetRepository() {
        @Override
        public List<Budget> list(UUID h) {
          return stored.stream().filter(b -> b.householdId().equals(h)).toList();
        }

        @Override
        public Optional<Budget> find(UUID h, UUID id) {
          return list(h).stream().filter(b -> b.id().equals(id)).findFirst();
        }

        @Override
        public boolean insert(Budget b) {
          boolean dup =
              stored.stream()
                  .anyMatch(
                      s ->
                          s.householdId().equals(b.householdId())
                              && java.util.Objects.equals(s.categoryId(), b.categoryId())
                              && s.period() == b.period());
          return !dup && stored.add(b);
        }

        @Override
        public boolean update(Budget b) {
          boolean removed = stored.removeIf(s -> s.id().equals(b.id()));
          return removed && stored.add(b);
        }

        @Override
        public boolean delete(UUID h, UUID id) {
          return stored.removeIf(s -> s.householdId().equals(h) && s.id().equals(id));
        }

        @Override
        public boolean categoryUsable(UUID h, UUID c) {
          return c.equals(FOOD);
        }
      };

  private final AnalyticsQueries queries =
      new AnalyticsQueries() {
        @Override
        public int monthStartDay(UUID householdId) {
          return startDay;
        }

        @Override
        public List<DayHour> dayHourTotals(
            UUID h, String c, Instant from, Instant to, boolean cash) {
          ranges.add(new Instant[] {from, to});
          return dayRows.stream().filter(r -> inRange(r.date(), from, to)).toList();
        }

        @Override
        public List<CategoryDay> categoryDayTotals(
            UUID h, String c, Instant from, Instant to, boolean cash, @Nullable UUID parent) {
          ranges.add(new Instant[] {from, to});
          return categoryRows.stream().filter(r -> inRange(r.date(), from, to)).toList();
        }

        @Override
        public List<DayTotal> incomeDayTotals(UUID h, String c, Instant from, Instant to) {
          return List.of();
        }

        @Override
        public List<MerchantRow> merchants(
            UUID h, String c, Instant from, Instant to, boolean cash, int limit) {
          return List.of();
        }

        @Override
        public int pendingReviewCount(UUID h, Instant from, Instant to) {
          return 0;
        }
      };

  private final BudgetService service =
      new BudgetService(
          repo, queries, () -> new CurrentHousehold.Household(HOUSEHOLD, "EGP"), CLOCK);

  private static boolean inRange(LocalDate d, Instant from, Instant to) {
    Instant at = d.atTime(12, 0).atZone(CAIRO).toInstant();
    return !at.isBefore(from) && at.isBefore(to);
  }

  private void spendFood(LocalDate d, long amount) {
    categoryRows.add(new CategoryDay(FOOD_REF, d, amount, 1));
    dayRows.add(new DayHour(d, 12, amount, 1));
  }

  private Budget seed(@Nullable UUID cat, BudgetPeriodKind kind, long amount, boolean rollover) {
    Budget b =
        new Budget(
            UUID.randomUUID(),
            HOUSEHOLD,
            cat,
            kind,
            amount,
            "EGP",
            rollover,
            List.of(50, 80, 100),
            Instant.parse("2026-01-01T00:00:00Z"));
    stored.add(b);
    return b;
  }

  @Test
  @DisplayName("REQ-AN-008 month_start_day 25: period is 25 Oct to 24 Nov with DST-correct bounds")
  void monthStartDay25() {
    startDay = 25;
    seed(null, BudgetPeriodKind.MONTHLY, 100_000, false);
    // In period: 25 Oct (first day) and 24 Nov (last day). Out: 24 Oct and 25 Nov.
    dayRows.add(new DayHour(LocalDate.of(2026, 10, 25), 12, 10_000, 1));
    dayRows.add(new DayHour(LocalDate.of(2026, 11, 24), 12, 20_000, 1));
    dayRows.add(new DayHour(LocalDate.of(2026, 10, 24), 12, 99_999, 1));
    dayRows.add(new DayHour(LocalDate.of(2026, 11, 25), 12, 88_888, 1));

    Status s = service.status("current").items().get(0);

    assertThat(s.period().label()).isEqualTo("2026-10");
    assertThat(s.period().from()).isEqualTo(LocalDate.of(2026, 10, 25));
    assertThat(s.period().to()).isEqualTo(LocalDate.of(2026, 11, 24));
    assertThat(s.spent()).isEqualTo(30_000);
    assertThat(s.remaining()).isEqualTo(70_000);
    assertThat(s.progress().progressBps()).isEqualTo(3_000);
    assertThat(s.daysLeft()).isEqualTo(30);
    // Clocks go back on 30 Oct 2026: starts at UTC+3, ends at UTC+2.
    Instant[] r = ranges.get(0);
    assertThat(r[0]).isEqualTo(Instant.parse("2026-10-24T21:00:00Z"));
    assertThat(r[1]).isEqualTo(Instant.parse("2026-11-24T22:00:00Z"));
  }

  @Test
  @DisplayName("REQ-AN-008 category budget sums the category rows; thresholds and on-track")
  void categoryBudget() {
    seed(FOOD, BudgetPeriodKind.MONTHLY, 10_000, false);
    spendFood(LocalDate.of(2026, 10, 3), 4_000);
    spendFood(LocalDate.of(2026, 10, 10), 4_500);

    Status s = service.status("current").items().get(0);

    assertThat(s.spent()).isEqualTo(8_500);
    assertThat(s.progress().progressBps()).isEqualTo(8_500);
    assertThat(s.progress().alertsFired()).containsExactly(50, 80);
    assertThat(s.remaining()).isEqualTo(1_500);
    // 8500 over 25 days projects past the 10000 limit.
    assertThat(s.onTrack()).isFalse();
    assertThat(s.daysLeft()).isEqualTo(6);
  }

  @Test
  @DisplayName("REQ-AN-008 overspent budget reports negative remaining and 100 percent")
  void overspent() {
    seed(null, BudgetPeriodKind.MONTHLY, 10_000, false);
    dayRows.add(new DayHour(LocalDate.of(2026, 10, 5), 9, 12_345, 1));

    Status s = service.status("2026-10").items().get(0);

    assertThat(s.remaining()).isEqualTo(-2_345);
    assertThat(s.progress().progressBps()).isEqualTo(12_345);
    assertThat(s.progress().thresholdReached()).isEqualTo(100);
  }

  @Test
  @DisplayName("REQ-AN-008 rollover carries last period's leftover, and overspend, forward")
  void rollover() {
    seed(null, BudgetPeriodKind.MONTHLY, 10_000, true);
    dayRows.add(new DayHour(LocalDate.of(2026, 9, 10), 9, 3_000, 1));
    dayRows.add(new DayHour(LocalDate.of(2026, 10, 10), 9, 5_000, 1));

    Status s = service.status("current").items().get(0);

    assertThat(s.rolledOver()).isEqualTo(7_000);
    assertThat(s.remaining()).isEqualTo(12_000);
    assertThat(s.progress().progressBps()).isEqualTo(2_941);

    dayRows.add(new DayHour(LocalDate.of(2026, 9, 11), 9, 12_000, 1));
    Status over = service.status("current").items().get(0);
    assertThat(over.rolledOver()).isEqualTo(-5_000);
    assertThat(over.remaining()).isEqualTo(0);
    assertThat(over.progress().progressBps()).isEqualTo(10_000);
  }

  @Test
  @DisplayName("REQ-AN-008 a budget created this month does not roll over")
  void noRolloverForNewBudget() {
    Budget b = seed(null, BudgetPeriodKind.MONTHLY, 10_000, true);
    stored.clear();
    stored.add(
        new Budget(
            b.id(),
            HOUSEHOLD,
            null,
            BudgetPeriodKind.MONTHLY,
            10_000,
            "EGP",
            true,
            b.alertThresholds(),
            Instant.parse("2026-10-05T00:00:00Z")));

    assertThat(service.status("current").items().get(0).rolledOver()).isZero();
  }

  @Test
  @DisplayName("REQ-AN-008 weekly budget uses the ISO week of today")
  void weekly() {
    seed(null, BudgetPeriodKind.WEEKLY, 5_000, false);
    // 2026-10-25 is a Sunday: the week is Mon 19 to Sun 25 Oct.
    dayRows.add(new DayHour(LocalDate.of(2026, 10, 19), 9, 1_000, 1));
    dayRows.add(new DayHour(LocalDate.of(2026, 10, 18), 9, 7_000, 1));

    Status s = service.status("current").items().get(0);

    assertThat(s.period().label()).isEqualTo("2026-W43");
    assertThat(s.spent()).isEqualTo(1_000);
    assertThat(s.daysLeft()).isZero();
  }

  @Test
  @DisplayName("REQ-AN-008 create validates amount, currency, category, thresholds")
  void validation() {
    assertThatThrownBy(() -> service.create(new Command(null, null, null, null, null, null)))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(() -> service.create(new Command(null, null, 0L, "EGP", null, null)))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(() -> service.create(new Command(null, null, 100L, "USD", null, null)))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(
            () -> service.create(new Command(UUID.randomUUID(), null, 100L, "EGP", null, null)))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(
            () -> service.create(new Command(null, null, 100L, "EGP", null, List.of(50, 50))))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(() -> service.create(new Command(null, null, 100L, "EGP", null, List.of(0))))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(
            () -> service.create(new Command(null, null, 2_000_000_000_000L, "EGP", null, null)))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(() -> service.status("nonsense")).isInstanceOf(ExpenseException.class);
  }

  @Test
  @DisplayName("REQ-AN-008 duplicate scope conflicts; update keeps category and period fixed")
  void conflictAndUpdate() {
    Budget b = service.create(new Command(FOOD, null, 5_000L, "EGP", null, List.of(90, 50)));
    assertThat(b.alertThresholds()).containsExactly(50, 90);
    assertThat(b.period()).isEqualTo(BudgetPeriodKind.MONTHLY);
    assertThatThrownBy(() -> service.create(new Command(FOOD, null, 1L, "EGP", null, null)))
        .isInstanceOf(ExpenseException.class)
        .extracting(e -> ((ExpenseException) e).kind())
        .isEqualTo(ExpenseException.Kind.CONFLICT);

    Budget u = service.update(b.id(), new Command(null, null, 9_000L, null, true, null));
    assertThat(u.amountMinor()).isEqualTo(9_000);
    assertThat(u.rollover()).isTrue();
    assertThat(u.alertThresholds()).containsExactly(50, 90);
    assertThatThrownBy(
            () ->
                service.update(
                    b.id(), new Command(null, BudgetPeriodKind.WEEKLY, null, null, null, null)))
        .isInstanceOf(ExpenseException.class);
    assertThatThrownBy(
            () ->
                service.update(
                    b.id(), new Command(UUID.randomUUID(), null, null, null, null, null)))
        .isInstanceOf(ExpenseException.class);

    service.delete(b.id());
    assertThatThrownBy(() -> service.get(b.id())).isInstanceOf(ExpenseException.class);
    assertThatThrownBy(() -> service.delete(b.id())).isInstanceOf(ExpenseException.class);
  }

  @Test
  @DisplayName("REQ-AN-008 monthly budget asked for a week resolves to the containing month")
  void monthlyForWeek() {
    AnalyticsPeriod week = AnalyticsPeriod.week(LocalDate.of(2026, 10, 19));
    assertThat(
            BudgetService.periodFor(BudgetPeriodKind.MONTHLY, week, 1, LocalDate.of(2026, 10, 25))
                .label())
        .isEqualTo("2026-10");
  }
}
