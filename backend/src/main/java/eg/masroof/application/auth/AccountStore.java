package eg.masroof.application.auth;

import java.util.Optional;
import java.util.UUID;

/** Port: login accounts. */
public interface AccountStore {

  /** Case-insensitive lookup by e-mail. */
  Optional<Account> findByEmail(String email);

  /** Lookup by app_user id. */
  Optional<Account> findByUserId(UUID userId);

  /** Whether any login exists yet. */
  default boolean hasAnyLogin() {
    return false;
  }

  /**
   * Creates an ADMIN member in the oldest household (creating one when none exists) with a login,
   * unless a login already exists. Returns whether it created one.
   */
  boolean createFirstAdmin(String email, String displayName, String passwordHash);
}
