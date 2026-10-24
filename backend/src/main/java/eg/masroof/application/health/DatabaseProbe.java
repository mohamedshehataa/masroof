package eg.masroof.application.health;

/** Outbound port: is the database reachable. Returns within 2 s and never throws. */
public interface DatabaseProbe {

  /**
   * Checks the database.
   *
   * @return false when the database is down or unknown
   */
  boolean isUp();
}
