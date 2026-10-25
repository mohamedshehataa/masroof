package eg.masroof.adapters.out.persistence;

import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.UnauthenticatedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default until STORY-009 adds authentication: no household can be derived, so every
 * household-scoped request is answered 401. The dev single-household switch replaces it.
 */
@Component
@ConditionalOnProperty(
    name = "masroof.dev.single-household",
    havingValue = "false",
    matchIfMissing = true)
final class UnauthenticatedHousehold implements CurrentHousehold {

  @Override
  public Household get() {
    throw new UnauthenticatedException();
  }
}
