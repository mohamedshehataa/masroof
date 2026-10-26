package eg.masroof.application.sms;

import java.util.Optional;
import java.util.UUID;

/** Port: maps a card or account last four to a payment account (and so to its owner). */
public interface SmsAccounts {

  /** A payment account and the member who owns it. */
  record AccountRef(UUID id, UUID memberId) {}

  /** The household's only account with this last four; empty when none or ambiguous. */
  Optional<AccountRef> findByLast4(UUID householdId, String last4);
}
