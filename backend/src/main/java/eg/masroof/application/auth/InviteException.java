package eg.masroof.application.auth;

import org.jspecify.annotations.Nullable;

/** An invite failure the REST adapter maps to a problem; messages never echo codes. */
public class InviteException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Failure classes. */
  public enum Kind {
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INVALID_FIELD
  }

  private final Kind kind;
  private final @Nullable String field;

  /** Creates a failure with a safe, fixed message. */
  public InviteException(Kind kind, String message, @Nullable String field) {
    super(message);
    this.kind = kind;
    this.field = field;
  }

  /** The class of failure. */
  public Kind kind() {
    return kind;
  }

  /** The offending request field, for validation failures. */
  public @Nullable String field() {
    return field;
  }
}
