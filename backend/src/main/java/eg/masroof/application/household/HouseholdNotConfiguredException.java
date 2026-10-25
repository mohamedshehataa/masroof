package eg.masroof.application.household;

/** No household exists yet (run the demo seed). */
public class HouseholdNotConfiguredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public HouseholdNotConfiguredException() {
    super("No household is set up");
  }
}
