package eg.masroof.adapters.in.rest;

import eg.masroof.application.auth.Caller;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.DevHousehold;
import eg.masroof.application.household.UnauthenticatedException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The household of the authenticated caller. Only when the dev single-household switch is on and
 * the request carried no token does it fall back to the dev provider; otherwise 401.
 */
@Component
final class CallerHousehold implements CurrentHousehold {

  private final Optional<DevHousehold> dev;

  CallerHousehold(Optional<DevHousehold> dev) {
    this.dev = dev;
  }

  @Override
  public Household get() {
    Caller caller = CallerContext.current();
    if (caller != null) {
      return new Household(caller.householdId(), caller.baseCurrency());
    }
    return dev.map(DevHousehold::get).orElseThrow(UnauthenticatedException::new);
  }

  /** Reads the caller the {@link AuthenticationFilter} stored on the current request. */
  static final class CallerContext {
    private CallerContext() {}

    static @Nullable Caller current() {
      RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
      Object value =
          attrs == null
              ? null
              : attrs.getAttribute(AuthenticationFilter.CALLER, RequestAttributes.SCOPE_REQUEST);
      return value instanceof Caller c ? c : null;
    }
  }
}
