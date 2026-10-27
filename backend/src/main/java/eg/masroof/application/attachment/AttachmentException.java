package eg.masroof.application.attachment;

/** An upload or storage failure the REST adapter maps to a problem+json response. */
public class AttachmentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The failure class (400/413/415/503). */
  public enum Kind {
    EMPTY,
    TOO_LARGE,
    UNSUPPORTED_TYPE,
    STORAGE_UNAVAILABLE
  }

  private final Kind kind;

  /** Creates an exception; the message never contains file content or names. */
  public AttachmentException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  /** The failure class. */
  public Kind kind() {
    return kind;
  }
}
