package eg.masroof.application.household;

/** The caller is not authenticated and no household can be derived (until STORY-009 adds auth). */
public class UnauthenticatedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public UnauthenticatedException() {
    super("Authentication is required");
  }
}
