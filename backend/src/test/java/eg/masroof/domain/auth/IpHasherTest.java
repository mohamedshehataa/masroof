package eg.masroof.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-SEC-007")
class IpHasherTest {

  private static IpHasher hasher(String key) {
    return new IpHasher(key.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("REQ-SEC-007 same ip and key give the same hash")
  void deterministic() {
    assertThat(hasher("k1").hash("203.0.113.9")).isEqualTo(hasher("k1").hash("203.0.113.9"));
  }

  @Test
  @DisplayName("REQ-SEC-007 a different key gives a different hash")
  void keyed() {
    assertThat(hasher("k1").hash("203.0.113.9")).isNotEqualTo(hasher("k2").hash("203.0.113.9"));
  }

  @Test
  @DisplayName(
      "REQ-SEC-007 the unsalted sha256 of the address does not match, so no offline lookup")
  void notPlainHash() throws NoSuchAlgorithmException {
    String plain =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("ip:203.0.113.9".getBytes(StandardCharsets.UTF_8)));
    assertThat(hasher("k1").hash("203.0.113.9")).isNotEqualTo(plain).hasSize(64);
  }

  @Test
  @DisplayName("REQ-SEC-007 hosts in one /24 or /48 share a hash; other networks differ")
  void prefixes() {
    IpHasher h = hasher("k1");
    assertThat(h.hash("203.0.113.9")).isEqualTo(h.hash("203.0.113.200"));
    assertThat(h.hash("203.0.114.9")).isNotEqualTo(h.hash("203.0.113.9"));
    assertThat(h.hash("2001:db8:1:2::1")).isEqualTo(h.hash("2001:db8:1:ffff::9"));
    assertThat(h.hash("2001:db8:2::1")).isNotEqualTo(h.hash("2001:db8:1::1"));
    assertThat(h.hash("not-an-ip")).hasSize(64);
  }

  @Test
  @DisplayName("REQ-SEC-007 derived keys depend on label")
  void derived() {
    byte[] m = "master-master-master-master-master".getBytes(StandardCharsets.UTF_8);
    assertThat(IpHasher.derivedFrom(m, "jwt").hash("1.2.3.4"))
        .isEqualTo(IpHasher.derivedFrom(m, "jwt").hash("1.2.3.4"))
        .isNotEqualTo(IpHasher.derivedFrom(m, "key").hash("1.2.3.4"));
  }
}
