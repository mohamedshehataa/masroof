package eg.masroof.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-SEC-001")
class JwtSecretPolicyTest {

  @Test
  @DisplayName("REQ-SEC-001 blank and unresolved values are missing")
  void missing() {
    assertThat(JwtSecretPolicy.problem("")).contains("is missing");
    assertThat(JwtSecretPolicy.problem("   ")).contains("is missing");
    assertThat(JwtSecretPolicy.problem("${MASROOF_JWT_SECRET}")).contains("is missing");
  }

  @Test
  @DisplayName("REQ-SEC-001 fewer than 32 bytes is weak, 32 is fine")
  void length() {
    assertThat(JwtSecretPolicy.problem("abcdefghijklmnopqrstuvwxyz01234")).isPresent(); // 31
    assertThat(JwtSecretPolicy.problem("abcdefghijklmnopqrstuvwxyz012345")).isEmpty(); // 32
  }

  @Test
  @DisplayName("REQ-SEC-001 placeholders and low-variety values are refused")
  void placeholders() {
    assertThat(JwtSecretPolicy.problem("changeme-generate-with-openssl-rand-base64-48"))
        .isPresent();
    assertThat(JwtSecretPolicy.problem("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).isPresent();
    assertThat(JwtSecretPolicy.problem("abababababababababababababababababababab")).isPresent();
  }

  @Test
  @DisplayName("REQ-SEC-001 variety boundary: 7 distinct characters fail, 8 pass")
  void varietyBoundary() {
    assertThat(JwtSecretPolicy.problem("abcdefgabcdefgabcdefgabcdefgabcdefgabcdefg")).isPresent();
    assertThat(JwtSecretPolicy.problem("abcdefghabcdefghabcdefghabcdefghabcdefgh")).isEmpty();
    assertThat(JwtSecretPolicy.problem("xchangemex-0123456789-abcdefghijklmnopqr")).isPresent();
    assertThat(JwtSecretPolicy.problem("xCHANGEMEx-0123456789-abcdefghijklmnopqr")).isPresent();
    assertThat(JwtSecretPolicy.problem("x-0123456789-abcdefghijklmnopqrstuvwxyz")).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-001 multi-byte secrets are measured in bytes, not characters")
  void byteLength() {
    // 16 two-byte characters = 32 bytes, with 16 distinct code points
    assertThat(JwtSecretPolicy.problem("àáâãäåæçèéêëìíîï")).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-001 a random base64 value passes and problems never echo the input")
  void randomOk() {
    assertThat(JwtSecretPolicy.problem("q3JrT9vXk2Lw8NzYp0Hc5Fd7Gm1Sb4UeAi6Oj+Rt/Wx=")).isEmpty();
    assertThat(JwtSecretPolicy.problem("zq9x"))
        .hasValueSatisfying(m -> assertThat(m).doesNotContain("zq9x"));
  }
}
