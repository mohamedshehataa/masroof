package eg.masroof.application.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: invites and the members they create. */
public interface InviteStore {

  /** A new invite; {@code codeHash} is the SHA-256 of the code. */
  record NewInvite(
      UUID id,
      UUID householdId,
      UUID createdBy,
      String codeHash,
      String displayName,
      @Nullable String email,
      @Nullable String phoneE164,
      Instant createdAt,
      Instant expiresAt) {

    /** Never prints the hash. */
    @Override
    public String toString() {
      return "NewInvite[id=" + id + ", householdId=" + householdId + "]";
    }
  }

  /** A stored invite without its secret. */
  record InviteView(
      UUID id,
      String displayName,
      @Nullable String email,
      @Nullable String phoneE164,
      String status,
      Instant createdAt,
      Instant expiresAt) {}

  /** A household member. */
  record MemberView(UUID id, UUID householdId, String displayName, String role) {}

  /** Result of creating an invite. */
  enum CreateResult {
    CREATED,
    TOO_MANY_PENDING,
    HOUSEHOLD_FULL
  }

  /** Result of accepting an invite. */
  sealed interface AcceptResult {
    /** The member and login were created and the invite consumed. */
    record Accepted(Account account) implements AcceptResult {}

    /** Unknown, expired, used or revoked: deliberately one case. */
    record Invalid() implements AcceptResult {}

    /** The e-mail already has a login; the invite stays usable. */
    record EmailTaken() implements AcceptResult {}

    /** The household already has the maximum number of members; the invite stays usable. */
    record Full() implements AcceptResult {}
  }

  /** Inserts {@code invite} unless the household has too many pending invites or members. */
  CreateResult create(NewInvite invite, int maxPending, int maxMembers, Instant now);

  /** Pending, unexpired invites of one household, newest first. */
  List<InviteView> listActive(UUID householdId, Instant now);

  /** Result of revoking an invite. */
  enum RevokeResult {
    REVOKED,
    ALREADY_REVOKED,
    ACCEPTED,
    NOT_FOUND
  }

  /**
   * Revokes a pending invite of that household. {@link RevokeResult#NOT_FOUND} also covers another
   * household's invite, so it is indistinguishable from an unknown id.
   */
  RevokeResult revoke(UUID householdId, UUID inviteId, Instant now);

  /**
   * Cheap read: is there a pending, unexpired invite with this hash whose e-mail hint (when set)
   * matches {@code email} case-insensitively? Lets the service skip the expensive password hash for
   * codes that cannot be claimed.
   */
  boolean claimable(String codeHash, Instant now, String email);

  /**
   * Atomically consumes the invite with {@code codeHash} and creates the member and login in the
   * inviter's household. Concurrent calls for one code produce exactly one {@link
   * AcceptResult.Accepted}.
   */
  AcceptResult accept(
      String codeHash,
      Instant now,
      String email,
      @Nullable String displayName,
      String passwordHash,
      int maxMembers);

  /** Members of one household, oldest first. */
  List<MemberView> members(UUID householdId);
}
