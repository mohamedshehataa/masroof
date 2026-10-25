package eg.masroof.application.expense;

import eg.masroof.domain.expense.Expense;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: persistence of expenses. Every method is scoped to one household. */
public interface ExpenseRepository {

  /** A remembered Idempotency-Key. */
  record IdempotencyRecord(String fingerprint, UUID expenseId, Instant createdAt) {}

  /** Inserts a new row and returns it as stored. */
  Expense insert(Expense expense);

  /** Finds a row, soft-deleted or not. */
  Optional<Expense> find(UUID householdId, UUID id);

  /** Writes all mutable columns of an existing row and returns it as stored. */
  Expense update(Expense expense);

  /** Soft-deletes a live row; false when it does not exist or is already deleted. */
  boolean softDelete(UUID householdId, UUID id, Instant now);

  /** Up to {@code limit + 1} rows in sort order after the cursor. */
  List<Expense> list(ExpenseFilter filter);

  /** Sum of amount_minor over the filtered set (cursor and limit ignored), excluding TRANSFER. */
  long totalMinor(ExpenseFilter filter);

  /** The remembered key, if any. */
  Optional<IdempotencyRecord> findIdempotency(UUID householdId, String key);

  /** Remembers a key; replaces an entry created before {@code staleBefore}. False if it lost. */
  boolean claimIdempotency(
      UUID householdId,
      String key,
      String fingerprint,
      UUID expenseId,
      Instant now,
      Instant staleBefore);
}
