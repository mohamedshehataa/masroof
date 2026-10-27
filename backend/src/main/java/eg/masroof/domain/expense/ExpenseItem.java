package eg.masroof.domain.expense;

import eg.masroof.domain.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One purchased item on an expense. {@code qty} is a quantity (scale 3), not money; {@code total}
 * is integral minor units in the expense currency.
 */
public record ExpenseItem(UUID id, String name, @Nullable BigDecimal qty, @Nullable Money total) {}
