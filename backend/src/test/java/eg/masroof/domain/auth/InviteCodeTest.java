package eg.masroof.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class InviteCodeTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 codes carry 256 bits, are URL-safe and never repeat")
  void entropyAndAlphabet() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      String code = InviteCode.generate(RANDOM);
      assertThat(code).hasSize(43).matches("[A-Za-z0-9_-]+");
      assertThat(seen.add(code)).isTrue();
    }
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 the stored form is a SHA-256 hex that does not contain the code")
  void hashed() {
    String code = InviteCode.generate(RANDOM);
    String hash = InviteCode.hash(code);
    assertThat(hash).hasSize(64).matches("[0-9a-f]+").doesNotContain(code);
    assertThat(InviteCode.hash(code)).isEqualTo(hash);
    // known vector
    assertThat(InviteCode.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 comparison accepts the right code and rejects near misses")
  void constantTimeCompare() {
    String code = InviteCode.generate(RANDOM);
    String hash = InviteCode.hash(code);
    assertThat(InviteCode.matches(code, hash)).isTrue();
    assertThat(InviteCode.matches(code + "x", hash)).isFalse();
    assertThat(InviteCode.matches(code.substring(1), hash)).isFalse();
    assertThat(InviteCode.constantTimeEquals(hash, hash.substring(1))).isFalse();
  }
}
