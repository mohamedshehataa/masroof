package eg.masroof.adapters.out.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fails the start when profile {@code prod} is active without a database password. A bare {@code
 * ${POSTGRES_PASSWORD}} placeholder is left unresolved by Spring Boot's binder (it would be sent to
 * Postgres literally), so the requirement is enforced here (ASVS 13.3.1).
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdDatabasePasswordGuard {

  ProdDatabasePasswordGuard(Environment environment) {
    String password = environment.getProperty("POSTGRES_PASSWORD");
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(
          "POSTGRES_PASSWORD must be set to a non-empty value when profile 'prod' is active");
    }
  }
}
