package eg.masroof.application.budget;

import eg.masroof.domain.budget.BudgetPeriodKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: budget storage, always scoped to one household. */
public interface BudgetRepository {

  /** A stored budget. {@code categoryId == null} is the household total. */
  record Budget(
      UUID id,
      UUID householdId,
      @Nullable UUID categoryId,
      BudgetPeriodKind period,
      long amountMinor,
      String currency,
      boolean rollover,
      List<Integer> alertThresholds,
      Instant createdAt) {}

  /** All budgets of the household, oldest first. */
  List<Budget> list(UUID householdId);

  /** One budget of the household. */
  Optional<Budget> find(UUID householdId, UUID id);

  /** Inserts; false when a budget for the same category and period already exists. */
  boolean insert(Budget budget);

  /** Updates amount, rollover and thresholds; false when the budget is not the household's. */
  boolean update(Budget budget);

  /** Deletes; false when the budget is not the household's. */
  boolean delete(UUID householdId, UUID id);

  /** True when the category belongs to the household and is not archived. */
  boolean categoryUsable(UUID householdId, UUID categoryId);
}
