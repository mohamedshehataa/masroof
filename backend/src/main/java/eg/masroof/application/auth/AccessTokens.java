package eg.masroof.application.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port: short-lived signed access tokens. */
public interface AccessTokens {

  /** The verified claims of an access token. */
  record Claims(UUID userId, UUID householdId, String role, Instant expiresAt) {}

  /** Lifetime of an issued token. */
  Duration ttl();

  /** Issues a token for the account. */
  String issue(Account account, Instant now);

  /** Verifies signature, algorithm, structure and expiry; empty for anything wrong. */
  Optional<Claims> verify(String token, Instant now);
}
