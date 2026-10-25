package eg.masroof.application.household;

import eg.masroof.application.household.CurrentHousehold.Household;

/** Port: the dev-only household for token-less requests (masroof.dev.single-household). */
public interface DevHousehold {

  /** The household to act for. */
  Household get();
}
