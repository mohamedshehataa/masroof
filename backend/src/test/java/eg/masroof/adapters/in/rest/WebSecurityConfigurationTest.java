package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
  @Tag("REQ-SEC-051")
  @DisplayName("REQ-SEC-051 X-Forwarded-For is trusted only from the configured proxy ranges")
  void clientIp() {
    ClientIp byDefault = new ClientIp("");
    assertThat(byDefault.of(req("172.18.0.5", "198.51.100.7"))).isEqualTo("198.51.100.7");
    assertThat(byDefault.of(req("172.18.0.5", "198.51.100.1, 198.51.100.7")))
        .isEqualTo("198.51.100.7");
    assertThat(byDefault.of(req("172.32.0.5", "198.51.100.7"))).isEqualTo("172.32.0.5");
    assertThat(byDefault.of(req("10.1.2.3", "198.51.100.7"))).isEqualTo("10.1.2.3");
    assertThat(byDefault.of(req("203.0.113.9", "198.51.100.1"))).isEqualTo("203.0.113.9");
    assertThat(byDefault.of(req("127.0.0.1", "198.51.100.7"))).isEqualTo("127.0.0.1");
    assertThat(byDefault.of(req("172.18.0.5", null))).isEqualTo("172.18.0.5");
    assertThat(byDefault.of(req("172.18.0.5", "  "))).isEqualTo("172.18.0.5");
    assertThat(byDefault.of(req("172.18.0.5", "a".repeat(80)))).isEqualTo("172.18.0.5");

    ClientIp custom = new ClientIp("10.0.0.5, 192.168.4.0/22, ::1/128");
    assertThat(custom.of(req("10.0.0.5", "198.51.100.7"))).isEqualTo("198.51.100.7");
    assertThat(custom.of(req("10.0.0.6", "198.51.100.7"))).isEqualTo("10.0.0.6");
    assertThat(custom.of(req("192.168.7.9", "198.51.100.7"))).isEqualTo("198.51.100.7");
    assertThat(custom.of(req("192.168.8.1", "198.51.100.7"))).isEqualTo("192.168.8.1");
    assertThat(custom.of(req("::1", "198.51.100.7"))).isEqualTo("198.51.100.7");
    assertThat(custom.of(req("172.18.0.5", "198.51.100.7"))).isEqualTo("172.18.0.5");

    assertThat(catchThrowable(() -> new ClientIp("nonsense")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(catchThrowable(() -> new ClientIp("10.0.0.0/99")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(catchThrowable(() -> new ClientIp("10.0.0.0/x")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @Tag("REQ-SEC-051")
  @DisplayName(
      "REQ-SEC-051 prod refuses trusted-proxy entries broader than /16 (IPv4) or /64 (IPv6)")
  void prodRefusesBroadProxyRanges() {
    assertThat(new ClientIp("172.28.0.10/32", true)).isNotNull();
    assertThat(new ClientIp("172.28.0.0/16", true)).isNotNull();
    assertThat(new ClientIp("fd00::/64", true)).isNotNull();
    for (String broad :
        new String[] {
          "10.0.0.0/8", "0.0.0.0/0", "::/0", "fd00::/48", "172.28.0.10/32, 10.0.0.0/15"
        }) {
      assertThat(catchThrowable(() -> new ClientIp(broad, true)))
          .as(broad)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("broader");
    }
    // outside prod the broad default still works
    assertThat(new ClientIp("10.0.0.0/8", false)).isNotNull();
    assertThat(catchThrowable(() -> new ClientIp("", true)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @Tag("REQ-SEC-051")
  @DisplayName("REQ-SEC-051 only a header-less loopback call is the container's own probe")
  void loopbackProbe() {
    assertThat(ClientIp.isLoopbackProbe(req("127.0.0.1", null))).isTrue();
    assertThat(ClientIp.isLoopbackProbe(req("::1", null))).isTrue();
    assertThat(ClientIp.isLoopbackProbe(req("127.0.0.1", "198.51.100.7"))).isFalse();
    assertThat(ClientIp.isLoopbackProbe(req("172.18.0.5", null))).isFalse();
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
