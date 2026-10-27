package eg.masroof.application.expense;

import eg.masroof.domain.expense.ExpenseItem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Port: persistence of the purchased items of expenses. Every method is household-scoped. */
public interface ExpenseItemRepository {

  /** Replaces all items of an expense with {@code items}, keeping their order. */
  void replace(UUID householdId, UUID expenseId, List<ExpenseItem> items);

  /** Items per expense id in stored order; expenses without items are absent. One query. */
  Map<UUID, List<ExpenseItem>> forExpenses(UUID householdId, Collection<UUID> expenseIds);
}
