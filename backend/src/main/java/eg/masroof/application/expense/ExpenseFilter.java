package eg.masroof.application.expense;

import eg.masroof.domain.expense.ExpenseSource;
import eg.masroof.domain.expense.ExpenseStatus;
import eg.masroof.domain.expense.ExpenseType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Everything the list query can filter, sort and page by. Dates are already UTC instants. */
public record ExpenseFilter(
    UUID householdId,
    @Nullable Instant fromInclusive,
    @Nullable Instant toExclusive,
    @Nullable UUID memberId,
    @Nullable UUID categoryId,
    boolean merchantFilter,
    @Nullable UUID accountId,
    @Nullable ExpenseSource source,
    @Nullable ExpenseStatus status,
    @Nullable ExpenseType type,
    @Nullable Long minAmountMinor,
    @Nullable Long maxAmountMinor,
    @Nullable String text,
    boolean includeDeleted,
    ExpenseSort sort,
    @Nullable Cursor after,
    int limit) {}
