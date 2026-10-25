package eg.masroof.application.sms;

import eg.masroof.domain.expense.ExpenseSource;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A stored message being processed. {@code body} is the untrusted text: it must never be logged,
 * and this record's {@code toString} hides it.
 */
public record RawMessage(
    UUID id,
    UUID householdId,
    @Nullable UUID memberId,
    ExpenseSource source,
    @Nullable String sender,
    String body,
    Instant receivedAt,
    int attempts,
    String baseCurrency) {

  @Override
  public String toString() {
    return "RawMessage[id=" + id + ", source=" + source + ", attempts=" + attempts + "]";
  }
}
