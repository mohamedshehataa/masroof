package eg.masroof.application.auth;

import eg.masroof.domain.auth.Role;
import java.util.UUID;

/** A login account joined with its member and household. */
public record Account(
    UUID userId,
    UUID memberId,
    UUID householdId,
    String baseCurrency,
    String displayName,
    Role role,
    String passwordHash) {

  /** Never prints the hash. */
  @Override
  public String toString() {
    return "Account[userId=" + userId + ", householdId=" + householdId + "]";
  }
}
