package eg.masroof.application.auth;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: security audit trail. Never receives e-mails, passwords or raw IP addresses. */
public interface AuditLog {

  /** Records {@code action} (UPPER_SNAKE) for a member when known, with the hashed client IP. */
  void record(String action, @Nullable UUID memberId, String ipHash);
}
