package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.AnalyticsApi;
import eg.masroof.adapters.in.rest.generated.model.AnalyticsSummary;
import eg.masroof.adapters.in.rest.generated.model.BudgetStatusList;
import eg.masroof.adapters.in.rest.generated.model.CategoryBreakdown;
import eg.masroof.adapters.in.rest.generated.model.CategoryTotal;
import eg.masroof.adapters.in.rest.generated.model.Forecast;
import eg.masroof.adapters.in.rest.generated.model.Heatmap;
import eg.masroof.adapters.in.rest.generated.model.HeatmapDaysInner;
import eg.masroof.adapters.in.rest.generated.model.Insights;
import eg.masroof.adapters.in.rest.generated.model.MemberBreakdown;
import eg.masroof.adapters.in.rest.generated.model.MerchantBreakdown;
import eg.masroof.adapters.in.rest.generated.model.MerchantTotal;
import eg.masroof.adapters.in.rest.generated.model.PeriodRange;
import eg.masroof.adapters.in.rest.generated.model.Settlement;
import eg.masroof.adapters.in.rest.generated.model.Trend;
import eg.masroof.adapters.in.rest.generated.model.TrendPoint;
import eg.masroof.application.analytics.AnalyticsReports;
import eg.masroof.application.analytics.AnalyticsReports.CategoryLine;
import eg.masroof.application.analytics.AnalyticsService;
import eg.masroof.application.analytics.AnalyticsService.InvalidAnalyticsRequestException;
import eg.masroof.application.budget.BudgetService;
import eg.masroof.domain.analytics.AnalyticsPeriod;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** /api/analytics (REQ-AN-001, -002, -004, -005, -006, -007); the rest is declared, not built. */
@RestController
class AnalyticsController implements AnalyticsApi {

  private final AnalyticsService service;
  private final BudgetService budgets;

  AnalyticsController(AnalyticsService service, BudgetService budgets) {
    this.service = service;
    this.budgets = budgets;
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (InvalidAnalyticsRequestException e) {
      throw new ApiProblemException(
          HttpStatus.BAD_REQUEST,
          "Validation failed",
          List.of(new ApiProblemException.FieldProblem(e.field(), String.valueOf(e.getMessage()))));
    }
  }

  @Override
  public ResponseEntity<AnalyticsSummary> getAnalyticsSummary(
      String period, @Nullable Boolean includeCashWithdrawals) {
    AnalyticsReports.Summary s = guarded(() -> service.summary(period, includeCashWithdrawals));
    AnalyticsSummary dto =
        new AnalyticsSummary()
            .period(range(s.period()))
            .total(s.total())
            .previousTotal(s.previousTotal())
            .dailyAverage(s.dailyAverage())
            .projectedTotal(s.projectedTotal())
            .projectedTotalWeekdayAware(s.projectedWeekdayAware())
            .income(s.income())
            .daysLeft(s.daysLeft())
            .pendingReviewCount(s.pendingReviewCount());
    s.deltaBps().ifPresent(dto::setDeltaPercentBps);
    dto.setTopCategories(s.topCategories().stream().map(AnalyticsController::category).toList());
    return ResponseEntity.ok(dto);
  }

  @Override
  public ResponseEntity<CategoryBreakdown> getAnalyticsByCategory(
      String period, @Nullable Boolean includeCashWithdrawals, @Nullable UUID parentId) {
    AnalyticsReports.CategoryReport r =
        guarded(() -> service.byCategory(period, includeCashWithdrawals, parentId));
    return ResponseEntity.ok(
        new CategoryBreakdown()
            .period(range(r.period()))
            .total(r.total())
            .items(r.items().stream().map(AnalyticsController::category).toList()));
  }

  @Override
  public ResponseEntity<Trend> getAnalyticsTrend(
      Integer months, @Nullable Boolean includeCashWithdrawals) {
    List<AnalyticsReports.TrendPoint> points =
        guarded(() -> service.trend(months, includeCashWithdrawals));
    return ResponseEntity.ok(
        new Trend().points(points.stream().map(AnalyticsController::trendPoint).toList()));
  }

  @Override
  public ResponseEntity<Heatmap> getAnalyticsHeatmap(
      String period, @Nullable Boolean includeCashWithdrawals) {
    AnalyticsReports.Heatmap h = guarded(() -> service.heatmap(period, includeCashWithdrawals));
    return ResponseEntity.ok(
        new Heatmap()
            .period(range(h.period()))
            .days(
                h.days().stream()
                    .map(
                        d ->
                            new HeatmapDaysInner().date(d.date()).total(d.total()).count(d.count()))
                    .toList())
            .weekdayHour(h.weekdayHour()));
  }

  @Override
  public ResponseEntity<Forecast> getAnalyticsForecast(String period) {
    AnalyticsReports.Forecast f = guarded(() -> service.forecast(period));
    return ResponseEntity.ok(
        new Forecast()
            .period(range(f.period()))
            .spentSoFar(f.spentSoFar())
            .linear(f.linear())
            .weekdayAware(f.weekdayAware())
            .upcomingRecurring(List.of()));
  }

  @Override
  public ResponseEntity<MerchantBreakdown> getAnalyticsByMerchant(String period, Integer limit) {
    AnalyticsReports.MerchantReport r = guarded(() -> service.merchants(period, limit));
    return ResponseEntity.ok(
        new MerchantBreakdown()
            .period(range(r.period()))
            .items(
                r.items().stream()
                    .map(
                        m ->
                            new MerchantTotal()
                                .name(m.name())
                                .total(m.total())
                                .visits(m.visits())
                                .averageTicket(m.averageTicket())
                                .categoryId(m.categoryId()))
                    .toList()));
  }

  // Declared in the contract, built later: 501 Problem+JSON.
  @Override
  public ResponseEntity<MemberBreakdown> getAnalyticsByMember(
      String period, @Nullable Boolean includeCashWithdrawals) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<BudgetStatusList> getAnalyticsBudgets(String period) {
    return ResponseEntity.ok(BudgetsController.dto(budgets.status(period)));
  }

  @Override
  public ResponseEntity<Insights> getAnalyticsInsights(String period) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<Settlement> getAnalyticsSettlement(String period) {
    throw ApiProblemException.notImplemented();
  }

  private static TrendPoint trendPoint(AnalyticsReports.TrendPoint p) {
    TrendPoint dto =
        new TrendPoint()
            .period(range(p.period()))
            .total(p.total())
            .income(p.income())
            .byCategory(p.byCategory().stream().map(AnalyticsController::category).toList());
    p.savingsRateBps().ifPresent(dto::setSavingsRateBps);
    return dto;
  }

  private static PeriodRange range(AnalyticsPeriod p) {
    return new PeriodRange().label(p.label()).from(p.from()).to(p.to());
  }

  private static CategoryTotal category(CategoryLine l) {
    CategoryTotal dto = new CategoryTotal();
    UUID id = l.category().id();
    if (id != null) {
      dto.setCategoryId(id);
    }
    l.deltaBps().ifPresent(dto::setDeltaPercentBps);
    return dto.nameEn(l.category().nameEn())
        .nameAr(l.category().nameAr())
        .color(l.category().color())
        .total(l.total())
        .previousTotal(l.previousTotal())
        .shareBps(l.shareBps())
        .count(l.count());
  }
}
