package eg.masroof.adapters.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class BcryptPasswordHasherTest {

  private final BcryptPasswordHasher hasher = new BcryptPasswordHasher();

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 a login attempt over 72 bytes does not match and does not throw")
  void overlongLoginDoesNotThrow() {
    String hash = hasher.hash("correct horse battery");
    assertThat(hasher.matches("correct horse battery", hash)).isTrue();
    assertThat(hasher.matches("a".repeat(73), hash)).isFalse();
    assertThat(hasher.matches("ب".repeat(64), hash)).isFalse();
  }
}
