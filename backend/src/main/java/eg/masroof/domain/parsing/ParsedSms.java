package eg.masroof.domain.parsing;

import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What a template extracted. {@code amount} is null only for kinds that never become an expense.
 * Only the last four digits of a card or account are ever kept; balances are not extracted.
 */
public record ParsedSms(
    SmsKind kind,
    @Nullable Money amount,
    @Nullable String merchant,
    @Nullable String last4,
    Instant occurredAt,
    PaymentMethod paymentMethod,
    String templateId,
    @Nullable String institution) {}
