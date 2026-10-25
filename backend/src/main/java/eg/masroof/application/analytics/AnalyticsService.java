package eg.masroof.application.analytics;

import eg.masroof.application.analytics.AnalyticsQueries.CategoryDay;
import eg.masroof.application.analytics.AnalyticsQueries.CategoryRef;
import eg.masroof.application.analytics.AnalyticsQueries.DayHour;
import eg.masroof.application.analytics.AnalyticsQueries.DayTotal;
import eg.masroof.application.analytics.AnalyticsReports.CategoryLine;
import eg.masroof.application.analytics.AnalyticsReports.CategoryReport;
import eg.masroof.application.analytics.AnalyticsReports.DayCell;
import eg.masroof.application.analytics.AnalyticsReports.Forecast;
import eg.masroof.application.analytics.AnalyticsReports.Heatmap;
import eg.masroof.application.analytics.AnalyticsReports.MerchantLine;
import eg.masroof.application.analytics.AnalyticsReports.MerchantReport;
import eg.masroof.application.analytics.AnalyticsReports.Summary;
import eg.masroof.application.analytics.AnalyticsReports.TrendPoint;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.CurrentHousehold.Household;
import eg.masroof.domain.analytics.AnalyticsPeriod;
import eg.masroof.domain.analytics.Projections;
import eg.masroof.domain.expense.BusinessCalendar;
import eg.masroof.domain.money.Money;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for the analytics read side (REQ-AN-001, -002, -004, -005, -006, -007). */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

  static final int MAX_TREND_MONTHS = 36;
  static final int TOP_CATEGORIES = 5;
  private static final int MAX_MERCHANTS = 200;

  private final AnalyticsQueries queries;
  private final CurrentHousehold currentHousehold;
  private final Clock clock;

  /** Wires the ports. */
  public AnalyticsService(
      AnalyticsQueries queries, CurrentHousehold currentHousehold, Clock clock) {
    this.queries = queries;
    this.currentHousehold = currentHousehold;
    this.clock = clock;
  }

  /** Raised for a malformed {@code period}, {@code months} or {@code limit}. */
  public static final class InvalidAnalyticsRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String field;

    InvalidAnalyticsRequestException(String field, String message) {
      super(message);
      this.field = field;
    }

    /** The offending query parameter. */
    public String field() {
      return field;
    }
  }

  private record Ctx(Household household, int startDay, LocalDate today) {}

  private Ctx ctx() {
    Household h = currentHousehold.get();
    return new Ctx(h, queries.monthStartDay(h.id()), BusinessCalendar.dateOf(clock.instant()));
  }

  private AnalyticsPeriod resolve(Ctx c, String spec) {
    try {
      return AnalyticsPeriod.parse(spec, c.today(), c.startDay());
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw new InvalidAnalyticsRequestException("period", "invalid period");
    }
  }

  private static boolean cash(@Nullable Boolean includeCash) {
    return Boolean.TRUE.equals(includeCash);
  }

  /** Period overview with comparison, daily average and projections. */
  public Summary summary(String periodSpec, @Nullable Boolean includeCash) {
    Ctx c = ctx();
    String cur = c.household().baseCurrency();
    UUID hid = c.household().id();
    AnalyticsPeriod period = resolve(c, periodSpec);
    AnalyticsPeriod prev = period.previous();
    boolean cash = cash(includeCash);

    List<DayHour> rows =
        queries.dayHourTotals(hid, cur, period.start(), period.endExclusive(), cash);
    int elapsed = period.elapsedDays(c.today());
    long[] daily = dailyElapsed(rows, period, elapsed);
    long total = rows.stream().mapToLong(DayHour::total).sum();
    long previousTotal =
        queries.dayHourTotals(hid, cur, prev.start(), prev.endExclusive(), cash).stream()
            .mapToLong(DayHour::total)
            .sum();
    List<CategoryDay> catRows =
        queries.categoryDayTotals(hid, cur, period.start(), period.endExclusive(), cash, null);
    List<CategoryDay> prevCatRows =
        queries.categoryDayTotals(hid, cur, prev.start(), prev.endExclusive(), cash, null);
    long income =
        queries.incomeDayTotals(hid, cur, period.start(), period.endExclusive()).stream()
            .mapToLong(DayTotal::total)
            .sum();
    List<CategoryLine> top =
        categoryLines(catRows, prevCatRows, cur).stream().limit(TOP_CATEGORIES).toList();
    int pending = queries.pendingReviewCount(hid, period.start(), period.endExclusive());
    return new Summary(
        period,
        Money.of(total, cur),
        Money.of(previousTotal, cur),
        Projections.deltaBps(total, previousTotal),
        Money.of(Projections.dailyAverage(total, elapsed), cur),
        Money.of(Projections.linear(total, elapsed, period.lengthDays()), cur),
        Money.of(Projections.weekdayAware(period.from(), daily, period.lengthDays()), cur),
        Money.of(income, cur),
        top,
        period.daysLeft(c.today()),
        pending);
  }

  /** Month-end projections only. */
  public Forecast forecast(String periodSpec) {
    Ctx c = ctx();
    String cur = c.household().baseCurrency();
    AnalyticsPeriod period = resolve(c, periodSpec);
    List<DayHour> rows =
        queries.dayHourTotals(
            c.household().id(), cur, period.start(), period.endExclusive(), false);
    int elapsed = period.elapsedDays(c.today());
    long total = rows.stream().mapToLong(DayHour::total).sum();
    return new Forecast(
        period,
        Money.of(total, cur),
        Money.of(Projections.linear(total, elapsed, period.lengthDays()), cur),
        Money.of(
            Projections.weekdayAware(
                period.from(), dailyElapsed(rows, period, elapsed), period.lengthDays()),
            cur));
  }

  private static long[] dailyElapsed(List<DayHour> rows, AnalyticsPeriod period, int elapsed) {
    long[] daily = new long[elapsed];
    for (DayHour r : rows) {
      int idx = (int) java.time.temporal.ChronoUnit.DAYS.between(period.from(), r.date());
      if (idx >= 0 && idx < elapsed) {
        daily[idx] += r.total();
      }
    }
    return daily;
  }

  /** Ranked categories with the change against the previous period. */
  public CategoryReport byCategory(
      String periodSpec, @Nullable Boolean includeCash, @Nullable UUID parentId) {
    Ctx c = ctx();
    String cur = c.household().baseCurrency();
    UUID hid = c.household().id();
    AnalyticsPeriod period = resolve(c, periodSpec);
    AnalyticsPeriod prev = period.previous();
    boolean cash = cash(includeCash);
    List<CategoryDay> now =
        queries.categoryDayTotals(hid, cur, period.start(), period.endExclusive(), cash, parentId);
    List<CategoryDay> before =
        queries.categoryDayTotals(hid, cur, prev.start(), prev.endExclusive(), cash, parentId);
    long total = now.stream().mapToLong(CategoryDay::total).sum();
    return new CategoryReport(period, Money.of(total, cur), categoryLines(now, before, cur));
  }

  /** Per-period totals for the last {@code months} household months, oldest first. */
  public List<TrendPoint> trend(int months, @Nullable Boolean includeCash) {
    if (months < 1 || months > MAX_TREND_MONTHS) {
      throw new InvalidAnalyticsRequestException("months", "months must be 1..36");
    }
    Ctx c = ctx();
    String cur = c.household().baseCurrency();
    UUID hid = c.household().id();
    boolean cash = cash(includeCash);
    List<AnalyticsPeriod> periods = new ArrayList<>();
    AnalyticsPeriod p = AnalyticsPeriod.currentMonth(c.today(), c.startDay());
    for (int i = 0; i < months; i++) {
      periods.add(0, p);
      p = p.previous();
    }
    AnalyticsPeriod first = periods.get(0);
    AnalyticsPeriod last = periods.get(periods.size() - 1);
    List<CategoryDay> cats =
        queries.categoryDayTotals(hid, cur, first.start(), last.endExclusive(), cash, null);
    List<DayTotal> incomes = queries.incomeDayTotals(hid, cur, first.start(), last.endExclusive());
    List<TrendPoint> points = new ArrayList<>();
    for (AnalyticsPeriod per : periods) {
      List<CategoryDay> in = cats.stream().filter(r -> within(per, r.date())).toList();
      long total = in.stream().mapToLong(CategoryDay::total).sum();
      long income =
          incomes.stream().filter(r -> within(per, r.date())).mapToLong(DayTotal::total).sum();
      points.add(
          new TrendPoint(
              per,
              Money.of(total, cur),
              Money.of(income, cur),
              Projections.savingsRateBps(income, total),
              categoryLines(in, List.of(), cur)));
    }
    return points;
  }

  private static boolean within(AnalyticsPeriod p, LocalDate d) {
    return !d.isBefore(p.from()) && !d.isAfter(p.to());
  }

  /** Spend per day of the period and the weekday x hour matrix (index 0 = Saturday). */
  public Heatmap heatmap(String periodSpec, @Nullable Boolean includeCash) {
    Ctx c = ctx();
    String cur = c.household().baseCurrency();
    AnalyticsPeriod period = resolve(c, periodSpec);
    List<DayHour> rows =
        queries.dayHourTotals(
            c.household().id(), cur, period.start(), period.endExclusive(), cash(includeCash));
    Map<LocalDate, long[]> perDay = new HashMap<>();
    long[][] matrix = new long[7][24];
    for (DayHour r : rows) {
      long[] cell = perDay.computeIfAbsent(r.date(), d -> new long[2]);
      cell[0] += r.total();
      cell[1] += r.count();
      matrix[weekdayIndex(r.date().getDayOfWeek())][r.hour()] += r.total();
    }
    List<DayCell> days = new ArrayList<>();
    for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
      long[] cell = perDay.getOrDefault(d, new long[2]);
      days.add(new DayCell(d, Money.of(cell[0], cur), (int) cell[1]));
    }
    List<List<Money>> weekdayHour = new ArrayList<>();
    for (long[] row : matrix) {
      List<Money> hours = new ArrayList<>();
      for (long v : row) {
        hours.add(Money.of(v, cur));
      }
      weekdayHour.add(hours);
    }
    return new Heatmap(period, days, weekdayHour);
  }

  /** Index 0 is Saturday, the first day of the Egyptian week. */
  static int weekdayIndex(DayOfWeek day) {
    return (day.getValue() + 1) % 7;
  }

  /** Top merchants by spend. */
  public MerchantReport merchants(String periodSpec, int limit) {
    if (limit < 1 || limit > MAX_MERCHANTS) {
      throw new InvalidAnalyticsRequestException("limit", "limit must be 1..200");
    }
    Ctx c = ctx();
    String cur = c.household().baseCurrency();
    AnalyticsPeriod period = resolve(c, periodSpec);
    List<MerchantLine> items =
        queries
            .merchants(c.household().id(), cur, period.start(), period.endExclusive(), false, limit)
            .stream()
            .map(
                m ->
                    new MerchantLine(
                        m.name(),
                        Money.of(m.total(), cur),
                        m.visits(),
                        Money.of(Projections.dailyAverage(m.total(), m.visits()), cur),
                        m.categoryId()))
            .toList();
    return new MerchantReport(period, items);
  }

  private static List<CategoryLine> categoryLines(
      List<CategoryDay> now, List<CategoryDay> before, String currency) {
    Map<@Nullable UUID, long[]> cur = new LinkedHashMap<>();
    Map<@Nullable UUID, CategoryRef> refs = new HashMap<>();
    for (CategoryDay r : now) {
      long[] acc = cur.computeIfAbsent(r.category().id(), k -> new long[2]);
      acc[0] += r.total();
      acc[1] += r.count();
      refs.putIfAbsent(r.category().id(), r.category());
    }
    Map<@Nullable UUID, Long> prev = new HashMap<>();
    for (CategoryDay r : before) {
      prev.merge(r.category().id(), r.total(), Long::sum);
    }
    long total = cur.values().stream().filter(a -> a[0] > 0).mapToLong(a -> a[0]).sum();
    List<CategoryLine> lines = new ArrayList<>();
    for (Map.Entry<@Nullable UUID, long[]> e : cur.entrySet()) {
      long amount = e.getValue()[0];
      if (amount <= 0) {
        continue;
      }
      long before0 = prev.getOrDefault(e.getKey(), 0L);
      lines.add(
          new CategoryLine(
              Objects.requireNonNull(refs.get(e.getKey())),
              Money.of(amount, currency),
              Money.of(before0, currency),
              Projections.shareBps(amount, total),
              (int) e.getValue()[1],
              Projections.deltaBps(amount, before0)));
    }
    lines.sort(
        Comparator.comparingLong((CategoryLine l) -> l.total().amountMinor())
            .reversed()
            .thenComparing(l -> l.category().nameEn()));
    return lines;
  }
}
