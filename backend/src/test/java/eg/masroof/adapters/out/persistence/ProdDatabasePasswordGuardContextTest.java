package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * REQ-OPS-143: the prod guard decides on the effective {@code spring.datasource.password}. Runs the
 * guard as the only user configuration (no datasource, no Docker).
 */
class ProdDatabasePasswordGuardContextTest {

  private static final String CANARY = "leak-canary-9f3";
  private static final String YML_MAPPING = "spring.datasource.password=${POSTGRES_PASSWORD}";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ProdDatabasePasswordGuard.class);

  private static List<Throwable> chain(Throwable failure) {
    List<Throwable> result = new ArrayList<>();
    Throwable current = failure;
    while (current != null && !result.contains(current)) {
      result.add(current);
      current = current.getCause();
    }
    return result;
  }

  private static void assertGuardFailure(Throwable failure) {
    assertThat(failure).as("startup failure").isNotNull();
    assertThat(chain(failure))
        .as("an IllegalStateException naming spring.datasource.password and POSTGRES_PASSWORD")
        .anySatisfy(
            t -> {
              assertThat(t).isInstanceOf(IllegalStateException.class);
              assertThat(t.getMessage())
                  .contains("spring.datasource.password")
                  .contains("POSTGRES_PASSWORD");
            });
  }

  private static void assertNoCanary(Throwable failure) {
    for (Throwable t : chain(failure)) {
      assertThat(String.valueOf(t.getMessage())).doesNotContain(CANARY);
    }
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (a) prod without any password fails naming the property")
  void prodWithoutPasswordFails() {
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> assertGuardFailure(context.getStartupFailure()));
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (b) prod with spring.datasource.password starts with one guard bean")
  void prodWithEffectivePasswordStarts() {
    runner
        .withPropertyValues("spring.profiles.active=prod", "spring.datasource.password=s3cret-test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).getBeans(ProdDatabasePasswordGuard.class).hasSize(1);
            });
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (c) no profile and no password starts without a guard bean")
  void noProfileStartsWithoutGuard() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ProdDatabasePasswordGuard.class);
        });
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (d) upper-case PROD does not activate the guard (case-sensitive)")
  void upperCaseProfileDoesNotActivateGuard() {
    runner
        .withPropertyValues("spring.profiles.active=PROD")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ProdDatabasePasswordGuard.class);
            });
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (e) prod with an empty password fails")
  void prodWithEmptyPasswordFails() {
    runner
        .withPropertyValues("spring.profiles.active=prod", "spring.datasource.password=")
        .run(context -> assertGuardFailure(context.getStartupFailure()));
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (e) prod with a whitespace-only password fails")
  void prodWithWhitespacePasswordFails() {
    runner
        .withPropertyValues("spring.profiles.active=prod", "spring.datasource.password=   ")
        .run(context -> assertGuardFailure(context.getStartupFailure()));
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (f) prod with an unresolved ${POSTGRES_PASSWORD} placeholder fails")
  void prodWithUnresolvedPlaceholderFails() {
    runner
        .withPropertyValues("spring.profiles.active=prod", YML_MAPPING)
        .run(context -> assertGuardFailure(context.getStartupFailure()));
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (g) prod with the yml mapping and POSTGRES_PASSWORD starts")
  void prodWithMappedPostgresPasswordStarts() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod", YML_MAPPING, "POSTGRES_PASSWORD=s3cret-test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).getBeans(ProdDatabasePasswordGuard.class).hasSize(1);
            });
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (h) empty SPRING_DATASOURCE_PASSWORD overrides the mapping and fails")
  void emptyEnvOverrideFailsWithoutLeakingPostgresPassword() {
    runner
        .withInitializer(
            context -> {
              var sources = context.getEnvironment().getPropertySources();
              sources.addFirst(
                  new SystemEnvironmentPropertySource(
                      "testSystemEnvironment",
                      Map.of("SPRING_DATASOURCE_PASSWORD", "", "POSTGRES_PASSWORD", CANARY)));
              sources.addLast(
                  new MapPropertySource(
                      "testApplicationConfig",
                      Map.of(
                          "spring.profiles.active",
                          "prod",
                          "spring.datasource.password",
                          "${POSTGRES_PASSWORD}")));
            })
        .run(
            context -> {
              assertGuardFailure(context.getStartupFailure());
              assertNoCanary(context.getStartupFailure());
            });
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 (i) a failing placeholder never leaks the value or keeps its exception")
  void unresolvablePlaceholderFailsWithoutLeakingValue() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "spring.datasource.password=" + CANARY + "${MISSING_VAR}")
        .run(
            context -> {
              assertGuardFailure(context.getStartupFailure());
              assertNoCanary(context.getStartupFailure());
            });
  }

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName(
      "REQ-OPS-143 prod with SPRING_DATASOURCE_PASSWORD alone and no POSTGRES_PASSWORD starts")
  void prodWithSpringDatasourcePasswordEnvAloneStarts() {
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "testSystemEnvironment",
                            Map.of("SPRING_DATASOURCE_PASSWORD", "s3cret-test"))))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).getBeans(ProdDatabasePasswordGuard.class).hasSize(1);
            });
  }
}
