package eg.masroof.application.auth;

import eg.masroof.domain.auth.Role;
import java.util.UUID;

/** The authenticated member behind a request, as re-read from the database. */
public record Caller(UUID userId, UUID memberId, UUID householdId, String baseCurrency, Role role) {

  /** Builds the caller from a stored account. */
  public static Caller of(Account a) {
    return new Caller(a.userId(), a.memberId(), a.householdId(), a.baseCurrency(), a.role());
  }
}
