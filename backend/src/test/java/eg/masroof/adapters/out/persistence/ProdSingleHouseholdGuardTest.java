package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("REQ-SEC-012")
class ProdSingleHouseholdGuardTest {

  private ApplicationContextRunner runner(String... profilesAndProps) {
    return new ApplicationContextRunner()
        .withUserConfiguration(ProdSingleHouseholdGuard.class)
        .withPropertyValues(profilesAndProps);
  }

  @Test
  @DisplayName("REQ-SEC-012 profile prod with the single-household switch fails the start")
  void prodWithSwitchFails() {
    runner("spring.profiles.active=prod", "masroof.dev.single-household=true")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalStateException.class)
                  .rootCause()
                  .hasMessageContaining("masroof.dev.single-household");
            });
  }

  @Test
  @DisplayName("REQ-SEC-012 profile prod without the switch, or with it false, starts")
  void prodWithoutSwitchStarts() {
    runner("spring.profiles.active=prod").run(ctx -> assertThat(ctx).hasNotFailed());
    runner("spring.profiles.active=prod", "masroof.dev.single-household=false")
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  @DisplayName("REQ-SEC-012 other profiles may use the switch")
  void devWithSwitchStarts() {
    runner("spring.profiles.active=dev", "masroof.dev.single-household=true")
        .run(ctx -> assertThat(ctx).hasNotFailed());
    runner("masroof.dev.single-household=true").run(ctx -> assertThat(ctx).hasNotFailed());
  }
}
