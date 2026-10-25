package eg.masroof.application.auth;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: refresh tokens, stored only as SHA-256 hex hashes. */
public interface RefreshTokenStore {

  /** Result of presenting a refresh token. */
  sealed interface Outcome {
    /** The token was valid and is now rotated; the successor is stored. */
    record Rotated(UUID userId) implements Outcome {}

    /** The token was already rotated: the whole family is revoked. */
    record Reuse() implements Outcome {}

    /** The token is past its expiry. */
    record Expired() implements Outcome {}

    /** Unknown or revoked. */
    record Invalid() implements Outcome {}
  }

  /** Starts a new family with one live token. */
  void insertNewFamily(UUID userId, String tokenHash, Instant expiresAt);

  /** Atomically rotates {@code oldHash} into {@code newHash} (same family) or reports why not. */
  Outcome rotate(String oldHash, String newHash, Instant newExpiresAt, Instant now);

  /** Revokes the family of {@code tokenHash} when it belongs to {@code userId}; otherwise no-op. */
  void revokeFamily(String tokenHash, @Nullable UUID userId, Instant now);

  /** Revokes every token of whoever owns {@code tokenHash}; unknown hashes are a no-op. */
  void revokeAllOfOwner(String tokenHash, Instant now);

  /** Revokes every token of the user. */
  void revokeAll(UUID userId, Instant now);
}
