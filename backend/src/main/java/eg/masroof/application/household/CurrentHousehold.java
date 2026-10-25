package eg.masroof.application.household;

import java.util.UUID;

/**
 * Port: which household the caller acts for. The dev adapter returns the first household; STORY-009
 * replaces it with one derived from the JWT.
 */
public interface CurrentHousehold {

  /** The household and its base currency. */
  Household get();

  /** Household id and base currency (ISO-4217). */
  record Household(UUID id, String baseCurrency) {}
}
