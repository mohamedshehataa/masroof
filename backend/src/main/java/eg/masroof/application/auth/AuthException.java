package eg.masroof.application.auth;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** An authentication failure the REST adapter maps to a problem. */
public class AuthException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Failure classes. */
  public enum Kind {
    INVALID_CREDENTIALS("Invalid credentials"),
    REFRESH_INVALID("Invalid refresh token"),
    REFRESH_EXPIRED("Refresh token expired"),
    RATE_LIMITED("Too Many Requests"),
    UNAUTHENTICATED("Unauthorized");

    private final String title;

    Kind(String title) {
      this.title = title;
    }

    String title() {
      return title;
    }
  }

  private final Kind kind;
  private final transient @Nullable Duration retryAfter;

  /** Creates a failure without a retry hint. */
  public AuthException(Kind kind) {
    this(kind, null);
  }

  /** Creates a failure, with the time to wait for {@link Kind#RATE_LIMITED}. */
  public AuthException(Kind kind, @Nullable Duration retryAfter) {
    super(kind.title());
    this.kind = kind;
    this.retryAfter = retryAfter;
  }

  /** The class of failure. */
  public Kind kind() {
    return kind;
  }

  /** The title to show. */
  public String title() {
    return kind.title();
  }

  /** How long to wait, for rate limits. */
  public @Nullable Duration retryAfter() {
    return retryAfter;
  }
}
