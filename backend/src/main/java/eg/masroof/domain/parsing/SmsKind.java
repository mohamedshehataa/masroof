package eg.masroof.domain.parsing;

import eg.masroof.domain.expense.ExpenseType;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** What a bank or wallet message says happened. */
public enum SmsKind {
  PURCHASE(ExpenseType.EXPENSE),
  FEE(ExpenseType.EXPENSE),
  CASH_WITHDRAWAL(ExpenseType.CASH_WITHDRAWAL),
  TRANSFER(ExpenseType.TRANSFER),
  REFUND(ExpenseType.REFUND),
  INCOME(ExpenseType.INCOME),
  /** A reversal cancels an earlier entry: a human decides, never an automatic expense. */
  REVERSAL(null),
  /** Declined payments, statements, reminders: recognised, but nothing to record. */
  INFO(null);

  private final @Nullable ExpenseType expenseType;

  SmsKind(@Nullable ExpenseType expenseType) {
    this.expenseType = expenseType;
  }

  /** The ledger type this kind creates; empty for kinds that never become an expense. */
  public Optional<ExpenseType> expenseType() {
    return Optional.ofNullable(expenseType);
  }
}
