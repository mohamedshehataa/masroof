package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * REQ-OPS-020 (ticketed from the STORY-144 review): Hikari's own password property overrides
 * spring.datasource.password, so an empty override must not slip past the prod guard.
 */
class ProdDatabasePasswordGuardHikariTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ProdDatabasePasswordGuard.class);

  @Test
  @Tag("REQ-OPS-020")
  @DisplayName("REQ-OPS-020 prod with an empty spring.datasource.hikari.password fails the start")
  void prodWithEmptyHikariPasswordFails() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "spring.datasource.password=s3cret-test",
            "spring.datasource.hikari.password=")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).as("startup failure").isNotNull();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("spring.datasource.hikari.password");
            });
  }
}
