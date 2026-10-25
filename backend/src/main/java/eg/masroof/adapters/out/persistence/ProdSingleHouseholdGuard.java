package eg.masroof.adapters.out.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fails the start when profile {@code prod} is active together with {@code
 * masroof.dev.single-household} set to anything but a clear false: that switch serves the first
 * household to anyone, so it must never run in production.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdSingleHouseholdGuard {

  ProdSingleHouseholdGuard(Environment environment) {
    if (DevSwitch.enabled(environment)) {
      throw new IllegalStateException(
          DevSwitch.PROPERTY + " must be unset or false when profile 'prod' is active");
    }
  }
}
