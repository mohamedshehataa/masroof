package eg.masroof.adapters.out.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fails the start when profile {@code prod} is active without an effective database password. The
 * guard checks {@code spring.datasource.password} as the {@link Environment} resolves it, so {@code
 * SPRING_DATASOURCE_PASSWORD} alone passes and an empty override of the {@code POSTGRES_PASSWORD}
 * mapping fails (ASVS 13.3.1). The value never appears in a message, and a resolver failure is not
 * kept as the cause because its message can embed part of the value.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdDatabasePasswordGuard {

  private static final String PROPERTY = "spring.datasource.password";

  ProdDatabasePasswordGuard(Environment environment) {
    String password;
    try {
      password = environment.getProperty(PROPERTY);
    } catch (IllegalArgumentException unresolvable) {
      password = null;
    }
    if (password == null || password.isBlank() || password.contains("${")) {
      throw new IllegalStateException(
          PROPERTY
              + " must resolve to a non-empty value when profile 'prod' is active"
              + " (set POSTGRES_PASSWORD or SPRING_DATASOURCE_PASSWORD)");
    }
  }
}
