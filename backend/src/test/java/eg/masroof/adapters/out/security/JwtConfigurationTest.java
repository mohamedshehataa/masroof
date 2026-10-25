package eg.masroof.adapters.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.application.auth.AccessTokens;
import eg.masroof.application.auth.PasswordHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("REQ-SEC-001")
class JwtConfigurationTest {

  private static final String GOOD = "q3JrT9vXk2Lw8NzYp0Hc5Fd7Gm1Sb4UeAi6Oj+Rt/Wx=";

  private ApplicationContextRunner runner(String... props) {
    return new ApplicationContextRunner()
        .withUserConfiguration(JwtConfiguration.class)
        .withPropertyValues(props);
  }

  @Test
  @DisplayName("REQ-SEC-001 prod without a secret fails the start without echoing anything")
  void prodMissing() {
    runner("spring.profiles.active=prod")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("MASROOF_JWT_SECRET")
                  .hasMessageContaining("is missing");
            });
    runner("spring.profiles.active=prod", "masroof.jwt.secret=")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("REQ-SEC-001 prod with a weak or placeholder secret fails the start")
  void prodWeak() {
    runner("spring.profiles.active=prod", "masroof.jwt.secret=too-short-secret")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .rootCause()
                  .hasMessageNotContaining("too-short-secret");
            });
    runner(
            "spring.profiles.active=prod",
            "masroof.jwt.secret=changeme-changeme-changeme-changeme-1")
        .run(ctx -> assertThat(ctx).hasFailed());
    runner("spring.profiles.active=prod", "masroof.jwt.secret=${NOPE_NOT_SET}")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("REQ-SEC-001 prod with a strong secret starts")
  void prodStrong() {
    runner("spring.profiles.active=prod", "masroof.jwt.secret=" + GOOD)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(AccessTokens.class).hasSingleBean(PasswordHasher.class);
            });
  }

  @Test
  @DisplayName(
      "REQ-SEC-001 outside prod a missing secret gets a random key, a weak one still fails")
  void devBehaviour() {
    runner().run(ctx -> assertThat(ctx).hasNotFailed());
    runner("masroof.jwt.secret=weak").run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("REQ-SEC-001 passwords are BCrypt cost 12 and verified")
  void bcrypt() {
    BcryptPasswordHasher hasher = new BcryptPasswordHasher();
    String hash = hasher.hash("correct horse battery");
    assertThat(hash).startsWith("$2b$12$");
    assertThat(hasher.matches("correct horse battery", hash)).isTrue();
    assertThat(hasher.matches("wrong", hash)).isFalse();
    assertThat(hasher.dummyHash()).startsWith("$2b$12$");
    assertThat(hasher.matches("anything", hasher.dummyHash())).isFalse();
  }
}
