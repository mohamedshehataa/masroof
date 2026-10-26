package eg.masroof.application.budget;

import eg.masroof.application.analytics.AnalyticsQueries;
import eg.masroof.application.analytics.AnalyticsQueries.CategoryDay;
import eg.masroof.application.analytics.AnalyticsQueries.DayHour;
import eg.masroof.application.budget.BudgetRepository.Budget;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.expense.ExpenseException.Issue;
import eg.masroof.application.expense.ExpenseException.Kind;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.CurrentHousehold.Household;
import eg.masroof.domain.analytics.AnalyticsPeriod;
import eg.masroof.domain.analytics.Projections;
import eg.masroof.domain.budget.BudgetPeriodKind;
import eg.masroof.domain.budget.BudgetProgress;
import eg.masroof.domain.expense.BusinessCalendar;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Budget CRUD and progress (REQ-AN-008), using the same spend rules as the analytics. */
@Service
@Transactional
public class BudgetService {

  static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
  static final int MAX_THRESHOLDS = 10;
  static final List<Integer> DEFAULT_THRESHOLDS = List.of(50, 80, 100);

  private final BudgetRepository repository;
  private final AnalyticsQueries queries;
  private final CurrentHousehold currentHousehold;
  private final Clock clock;

  /** Wires the ports. */
  public BudgetService(
      BudgetRepository repository,
      AnalyticsQueries queries,
      CurrentHousehold currentHousehold,
      Clock clock) {
    this.repository = repository;
    this.queries = queries;
    this.currentHousehold = currentHousehold;
    this.clock = clock;
  }

  /** Fields of a create or update; absent fields are null. */
  public record Command(
      @Nullable UUID categoryId,
      @Nullable BudgetPeriodKind period,
      @Nullable Long amountMinor,
      @Nullable String currency,
      @Nullable Boolean rollover,
      @Nullable List<Integer> alertThresholds) {}

  /** Progress of one budget in its period. */
  public record Status(
      Budget budget,
      AnalyticsPeriod period,
      long spent,
      long remaining,
      long rolledOver,
      BudgetProgress progress,
      boolean onTrack,
      int daysLeft) {}

  /** Progress of all budgets for a requested period. */
  public record StatusList(AnalyticsPeriod period, List<Status> items) {}

  private static ExpenseException bad(String field, String message) {
    return new ExpenseException(
        Kind.BAD_REQUEST, "Bad Request", "Validation failed", List.of(new Issue(field, message)));
  }

  /** Lists budgets. */
  @Transactional(readOnly = true)
  public List<Budget> list() {
    return repository.list(currentHousehold.get().id());
  }

  /** One budget; 404 when it is not the household's. */
  @Transactional(readOnly = true)
  public Budget get(UUID id) {
    return repository
        .find(currentHousehold.get().id(), id)
        .orElseThrow(() -> ExpenseException.notFound("Budget"));
  }

  /** Creates a budget. */
  public Budget create(Command c) {
    Household h = currentHousehold.get();
    long amount = requireAmount(c.amountMinor(), c.currency(), h);
    UUID category = c.categoryId();
    if (category != null && !repository.categoryUsable(h.id(), category)) {
      throw bad("categoryId", "is not an active category of this household");
    }
    Budget b =
        new Budget(
            UUID.randomUUID(),
            h.id(),
            category,
            c.period() == null ? BudgetPeriodKind.MONTHLY : c.period(),
            amount,
            h.baseCurrency(),
            Boolean.TRUE.equals(c.rollover()),
            thresholds(c.alertThresholds()),
            clock.instant());
    if (!repository.insert(b)) {
      throw new ExpenseException(
          Kind.CONFLICT,
          "Conflict",
          "A budget for this category and period already exists",
          List.of());
    }
    return get(b.id());
  }

  /** Updates amount, rollover and thresholds; the category and period are fixed. */
  public Budget update(UUID id, Command c) {
    Household h = currentHousehold.get();
    Budget old = repository.find(h.id(), id).orElseThrow(() -> ExpenseException.notFound("Budget"));
    if (c.categoryId() != null && !c.categoryId().equals(old.categoryId())) {
      throw bad("categoryId", "cannot be changed; delete and recreate the budget");
    }
    if (c.period() != null && c.period() != old.period()) {
      throw bad("period", "cannot be changed; delete and recreate the budget");
    }
    long amount =
        c.amountMinor() == null && c.currency() == null
            ? old.amountMinor()
            : requireAmount(
                c.amountMinor() == null ? Long.valueOf(old.amountMinor()) : c.amountMinor(),
                c.currency(),
                h);
    Budget b =
        new Budget(
            old.id(),
            old.householdId(),
            old.categoryId(),
            old.period(),
            amount,
            old.currency(),
            c.rollover() == null ? old.rollover() : c.rollover(),
            c.alertThresholds() == null ? old.alertThresholds() : thresholds(c.alertThresholds()),
            old.createdAt());
    if (!repository.update(b)) {
      throw ExpenseException.notFound("Budget");
    }
    return b;
  }

  /** Deletes a budget. */
  public void delete(UUID id) {
    if (!repository.delete(currentHousehold.get().id(), id)) {
      throw ExpenseException.notFound("Budget");
    }
  }

  private static long requireAmount(@Nullable Long amount, @Nullable String currency, Household h) {
    if (amount == null) {
      throw bad("amount", "is required");
    }
    if (amount <= 0 || amount > MAX_AMOUNT_MINOR) {
      throw bad("amount", "must be positive and at most 1,000,000,000,000 minor units");
    }
    if (currency != null && !currency.equals(h.baseCurrency())) {
      throw bad("amount.currency", "must be the household currency " + h.baseCurrency());
    }
    return amount;
  }

  static List<Integer> thresholds(@Nullable List<Integer> given) {
    if (given == null) {
      return DEFAULT_THRESHOLDS;
    }
    if (given.size() > MAX_THRESHOLDS) {
      throw bad("alertThresholds", "at most 10 thresholds");
    }
    Set<Integer> seen = new HashSet<>();
    for (Integer t : given) {
      if (t == null || t < 1 || t > 200 || !seen.add(t)) {
        throw bad("alertThresholds", "must be unique percentages between 1 and 200");
      }
    }
    return given.stream().sorted().toList();
  }

  /** Progress of every budget for {@code periodSpec} (analytics period syntax). */
  @Transactional(readOnly = true)
  public StatusList status(String periodSpec) {
    Household h = currentHousehold.get();
    int startDay = queries.monthStartDay(h.id());
    LocalDate today = BusinessCalendar.dateOf(clock.instant());
    AnalyticsPeriod requested = parse(periodSpec, today, startDay);
    List<Status> items =
        repository.list(h.id()).stream()
            .map(b -> statusOf(b, requested, startDay, today, h))
            .toList();
    return new StatusList(requested, items);
  }

  /** Progress of one budget for {@code periodSpec}. */
  @Transactional(readOnly = true)
  public Status status(UUID id, String periodSpec) {
    Budget b = get(id);
    Household h = currentHousehold.get();
    int startDay = queries.monthStartDay(h.id());
    LocalDate today = BusinessCalendar.dateOf(clock.instant());
    return statusOf(b, parse(periodSpec, today, startDay), startDay, today, h);
  }

  private static AnalyticsPeriod parse(String spec, LocalDate today, int startDay) {
    try {
      return AnalyticsPeriod.parse(spec, today, startDay);
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw bad("period", "invalid period");
    }
  }

  private Status statusOf(
      Budget b, AnalyticsPeriod requested, int startDay, LocalDate today, Household h) {
    AnalyticsPeriod period = periodFor(b.period(), requested, startDay, today);
    long spent = spent(h, b.categoryId(), period);
    long rolled = 0;
    if (b.rollover()) {
      AnalyticsPeriod prev = period.previous();
      if (b.createdAt().isBefore(prev.endExclusive())) {
        rolled = Math.subtractExact(b.amountMinor(), spent(h, b.categoryId(), prev));
      }
    }
    long limit = Math.addExact(b.amountMinor(), rolled);
    BudgetProgress progress = BudgetProgress.of(spent, limit, b.alertThresholds());
    int elapsed = period.elapsedDays(today);
    long projected = Projections.linear(spent, elapsed, period.lengthDays());
    return new Status(
        b,
        period,
        spent,
        Math.subtractExact(limit, spent),
        rolled,
        progress,
        projected <= limit,
        period.daysLeft(today));
  }

  static AnalyticsPeriod periodFor(
      BudgetPeriodKind kind, AnalyticsPeriod requested, int startDay, LocalDate today) {
    if (kind == BudgetPeriodKind.MONTHLY) {
      return requested.week() ? AnalyticsPeriod.currentMonth(requested.to(), startDay) : requested;
    }
    if (requested.week()) {
      return requested;
    }
    LocalDate anchor = today.isAfter(requested.to()) ? requested.to() : today;
    if (anchor.isBefore(requested.from())) {
      anchor = requested.from();
    }
    return AnalyticsPeriod.week(anchor.with(DayOfWeek.MONDAY));
  }

  /** Same eligibility as the analytics: net of refunds, no transfers, no cash withdrawals. */
  private long spent(Household h, @Nullable UUID categoryId, AnalyticsPeriod period) {
    if (categoryId == null) {
      return queries
          .dayHourTotals(h.id(), h.baseCurrency(), period.start(), period.endExclusive(), false)
          .stream()
          .mapToLong(DayHour::total)
          .sum();
    }
    return queries
        .categoryDayTotals(
            h.id(), h.baseCurrency(), period.start(), period.endExclusive(), false, categoryId)
        .stream()
        .mapToLong(CategoryDay::total)
        .sum();
  }
}
