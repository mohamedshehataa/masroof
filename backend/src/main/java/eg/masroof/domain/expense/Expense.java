package eg.masroof.domain.expense;

import eg.masroof.domain.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One ledger entry. {@code amount} is in the household base currency. */
public record Expense(
    UUID id,
    UUID householdId,
    ExpenseType type,
    Money amount,
    Instant occurredAt,
    @Nullable UUID categoryId,
    @Nullable UUID paymentAccountId,
    @Nullable UUID paidByMemberId,
    PaymentMethod paymentMethod,
    @Nullable String merchantRaw,
    @Nullable String notes,
    List<String> tags,
    ExpenseStatus status,
    ExpenseSource source,
    @Nullable Instant deletedAt,
    Instant createdAt,
    Instant updatedAt) {}
