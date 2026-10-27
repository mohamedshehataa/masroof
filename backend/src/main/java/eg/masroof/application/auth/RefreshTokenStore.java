package eg.masroof.application.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: refresh tokens, stored only as SHA-256 hex hashes. */
public interface RefreshTokenStore {

  /** Result of presenting a refresh token. */
  sealed interface Outcome {
    /**
     * The token was valid and is now rotated, or it was re-presented inside the reuse grace window
     * and got a further successor in the same family. The successor is stored either way.
     */
    record Rotated(UUID userId) implements Outcome {}

    /** The token was rotated earlier, outside the grace window or over its cap: family revoked. */
    record Reuse() implements Outcome {}

    /** The token is past its expiry. */
    record Expired() implements Outcome {}

    /** Unknown or revoked. */
    record Invalid() implements Outcome {}
  }

  /** Starts a new family with one live token. */
  void insertNewFamily(UUID userId, String tokenHash, Instant expiresAt);

  /**
   * Atomically rotates {@code oldHash} into {@code newHash} (same family) or reports why not.
   *
   * <p>Checks run on the locked row in this order: unknown or revoked is {@code Invalid}; past
   * expiry is {@code Expired}; never rotated is a normal rotation. An already rotated token
   * presented less than {@code reuseGrace} after its first rotation (the window is measured from
   * {@code rotated_at}, which never moves) is a retry or a concurrent duplicate, not theft: no
   * revocation, a further successor is stored and {@code Rotated} is returned, at most {@code
   * maxGraceReissues} times per token and with an {@code REFRESH_GRACE} audit row each. Otherwise
   * the family is revoked with a {@code REFRESH_REUSE} audit row and {@code Reuse} is returned.
   */
  Outcome rotate(
      String oldHash,
      String newHash,
      Instant newExpiresAt,
      Instant now,
      Duration reuseGrace,
      int maxGraceReissues);

  /** Revokes the family of {@code tokenHash} when it belongs to {@code userId}; otherwise no-op. */
  void revokeFamily(String tokenHash, @Nullable UUID userId, Instant now);

  /** Revokes every token of whoever owns {@code tokenHash}; unknown hashes are a no-op. */
  void revokeAllOfOwner(String tokenHash, Instant now);

  /** Revokes every token of the user. */
  void revokeAll(UUID userId, Instant now);
}
