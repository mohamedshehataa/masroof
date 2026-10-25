package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

class WebSecurityConfigurationTest {

  @Test
  @Tag("REQ-SEC-010")
  @DisplayName("REQ-SEC-010 a wildcard CORS origin under prod refuses to start")
  void prodWildcard() {
    new ApplicationContextRunner()
        .withUserConfiguration(WebSecurityConfiguration.class)
        .withPropertyValues("spring.profiles.active=prod", "masroof.cors.allowed-origins=*")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("CORS_ALLOWED_ORIGINS");
            });
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 X-Forwarded-For is trusted only from a private or loopback peer")
  void clientIp() {
    assertThat(ClientIp.of(req("203.0.113.9", "198.51.100.1"))).isEqualTo("203.0.113.9");
    assertThat(ClientIp.of(req("10.1.2.3", "198.51.100.1, 198.51.100.7")))
        .isEqualTo("198.51.100.7");
    assertThat(ClientIp.of(req("172.18.0.5", "198.51.100.7"))).isEqualTo("198.51.100.7");
    assertThat(ClientIp.of(req("127.0.0.1", "198.51.100.7"))).isEqualTo("198.51.100.7");
    assertThat(ClientIp.of(req("::1", "198.51.100.7"))).isEqualTo("198.51.100.7");
    assertThat(ClientIp.of(req("10.1.2.3", null))).isEqualTo("10.1.2.3");
    assertThat(ClientIp.of(req("10.1.2.3", "  "))).isEqualTo("10.1.2.3");
    assertThat(ClientIp.of(req("10.1.2.3", "a".repeat(80)))).isEqualTo("10.1.2.3");
  }

  private static HttpServletRequest req(String peer, @Nullable String forwarded) {
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setRemoteAddr(peer);
    if (forwarded != null) {
      r.addHeader("X-Forwarded-For", forwarded);
    }
    return r;
  }
}
