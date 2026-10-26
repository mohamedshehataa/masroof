package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.BudgetsApi;
import eg.masroof.adapters.in.rest.generated.model.Budget;
import eg.masroof.adapters.in.rest.generated.model.BudgetInput;
import eg.masroof.adapters.in.rest.generated.model.BudgetPeriod;
import eg.masroof.adapters.in.rest.generated.model.BudgetStatus;
import eg.masroof.adapters.in.rest.generated.model.BudgetStatusList;
import eg.masroof.adapters.in.rest.generated.model.ListBudgets200Response;
import eg.masroof.adapters.in.rest.generated.model.PeriodRange;
import eg.masroof.application.budget.BudgetRepository;
import eg.masroof.application.budget.BudgetService;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.domain.analytics.AnalyticsPeriod;
import eg.masroof.domain.budget.BudgetPeriodKind;
import eg.masroof.domain.money.Money;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** /api/budgets (REQ-AN-008). Progress lives on /api/analytics/budgets. */
@RestController
class BudgetsController implements BudgetsApi {

  private final BudgetService service;

  BudgetsController(BudgetService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListBudgets200Response> listBudgets() {
    return ResponseEntity.ok(
        new ListBudgets200Response()
            .items(service.list().stream().map(BudgetsController::dto).toList()));
  }

  @Override
  public ResponseEntity<Budget> createBudget(BudgetInput input) {
    Budget created = dto(service.create(command(input)));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/budgets/" + created.getId()))
        .body(created);
  }

  @Override
  public ResponseEntity<Budget> getBudget(UUID budgetId) {
    return ResponseEntity.ok(dto(service.get(budgetId)));
  }

  @Override
  public ResponseEntity<Budget> updateBudget(UUID budgetId, BudgetInput input) {
    return ResponseEntity.ok(dto(service.update(budgetId, command(input))));
  }

  @Override
  public ResponseEntity<Void> deleteBudget(UUID budgetId) {
    service.delete(budgetId);
    return ResponseEntity.noContent().build();
  }

  private static BudgetService.Command command(BudgetInput in) {
    BudgetPeriod period = in.getPeriod();
    if (period == BudgetPeriod.CUSTOM) {
      throw ExpenseException.invalid("period", "CUSTOM budgets are not supported yet");
    }
    Money amount = in.getAmount();
    return new BudgetService.Command(
        in.getCategoryId(),
        period == null ? null : BudgetPeriodKind.valueOf(period.name()),
        amount == null ? null : amount.amountMinor(),
        amount == null ? null : amount.currency(),
        in.getRollover(),
        in.getAlertThresholds());
  }

  static Budget dto(BudgetRepository.Budget b) {
    Budget dto =
        new Budget()
            .id(b.id())
            .householdId(b.householdId())
            .period(BudgetPeriod.valueOf(b.period().name()))
            .amount(Money.of(b.amountMinor(), b.currency()))
            .rollover(b.rollover())
            .alertThresholds(b.alertThresholds());
    UUID category = b.categoryId();
    if (category != null) {
      dto.setCategoryId(category);
    }
    return dto;
  }

  static BudgetStatusList dto(BudgetService.StatusList list) {
    return new BudgetStatusList()
        .period(range(list.period()))
        .items(list.items().stream().map(BudgetsController::dto).toList());
  }

  private static BudgetStatus dto(BudgetService.Status s) {
    String cur = s.budget().currency();
    return new BudgetStatus()
        .budget(dto(s.budget()))
        .spent(Money.of(s.spent(), cur))
        .remaining(Money.of(s.remaining(), cur))
        .rolledOver(Money.of(s.rolledOver(), cur))
        .progressBps(s.progress().progressBps())
        .alertsFired(s.progress().alertsFired())
        .onTrack(s.onTrack())
        .daysLeft(s.daysLeft())
        .periodRange(range(s.period()));
  }

  private static PeriodRange range(AnalyticsPeriod p) {
    return new PeriodRange().label(p.label()).from(p.from()).to(p.to());
  }
}
