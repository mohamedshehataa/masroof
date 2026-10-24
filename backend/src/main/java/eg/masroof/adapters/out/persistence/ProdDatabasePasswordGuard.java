package eg.masroof.adapters.out.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fails the start when profile {@code prod} is active without an effective database password. The
 * guard checks {@code spring.datasource.password} as the {@link Environment} resolves it, so {@code
 * SPRING_DATASOURCE_PASSWORD} alone passes and an empty override of the {@code POSTGRES_PASSWORD}
 * mapping fails (ASVS 13.3.1). {@code spring.datasource.hikari.password} overrides it, so when set
 * it must be non-blank too. The value never appears in a message, and a resolver failure is not
 * kept as the cause because its message can embed part of the value.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdDatabasePasswordGuard {

  private static final String PROPERTY = "spring.datasource.password";
  private static final String HIKARI_PROPERTY = "spring.datasource.hikari.password";

  ProdDatabasePasswordGuard(Environment environment) {
    require(environment, PROPERTY, true);
    require(environment, HIKARI_PROPERTY, false);
  }

  private static void require(Environment environment, String property, boolean mandatory) {
    String password;
    try {
      password = environment.getProperty(property);
    } catch (IllegalArgumentException unresolvable) {
      password = "${";
    }
    boolean invalid = password == null ? mandatory : password.isBlank() || password.contains("${");
    if (invalid) {
      throw new IllegalStateException(
          property
              + " must resolve to a non-empty value without '${' when profile 'prod' is active"
              + " (set POSTGRES_PASSWORD or SPRING_DATASOURCE_PASSWORD)");
    }
  }
}
