package eg.masroof.adapters.out.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fails the start when profile {@code prod} is active together with {@code
 * masroof.dev.single-household=true}: that switch serves the first household to anyone, so it must
 * never run in production.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdSingleHouseholdGuard {

  private static final String PROPERTY = "masroof.dev.single-household";

  ProdSingleHouseholdGuard(Environment environment) {
    if (Boolean.parseBoolean(environment.getProperty(PROPERTY, "false"))) {
      throw new IllegalStateException(
          PROPERTY + " must not be true when profile 'prod' is active (no authentication yet)");
    }
  }
}
