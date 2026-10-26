package eg.masroof.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.domain.auth.PasswordPolicy.Violation;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 accepts a long passphrase, short ones fail")
  void length() {
    assertThat(PasswordPolicy.check("correct horse battery")).isEmpty();
    assertThat(PasswordPolicy.check("a long bootstrap phrase")).isEmpty();
    assertThat(PasswordPolicy.check("11 chars!!!")).contains(Violation.TOO_SHORT);
    assertThat(PasswordPolicy.check(" ".repeat(12))).contains(Violation.TOO_SHORT);
    assertThat(PasswordPolicy.check("\t\n\t\n\t\n\t\n\t\n\t\n")).contains(Violation.TOO_SHORT);
    assertThat(PasswordPolicy.check("x7!kQ2#mZp9L")).isEmpty(); // exactly 12
  }

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 the limit is 72 UTF-8 bytes, not characters")
  void bytes() {
    assertThat(PasswordPolicy.check("k".repeat(72))).isEqualTo(Optional.empty());
    assertThat(PasswordPolicy.check("k".repeat(73))).contains(Violation.TOO_LONG);
    assertThat(PasswordPolicy.check("ب".repeat(36))).isEmpty(); // 72 bytes
    assertThat(PasswordPolicy.check("ب".repeat(37))).contains(Violation.TOO_LONG);
    assertThat(PasswordPolicy.check("ب".repeat(64))).contains(Violation.TOO_LONG);
    assertThat(PasswordPolicy.check("valid passphrase\0 here"))
        .contains(Violation.INVALID_CHARACTER);
  }

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 common passwords fail, case-folded and with trailing digits or symbols")
  void common() {
    assertThat(PasswordPolicy.check("password1234")).contains(Violation.COMMON);
    assertThat(PasswordPolicy.check("PASSWORD1234")).contains(Violation.COMMON);
    assertThat(PasswordPolicy.check("Password1234!")).contains(Violation.COMMON);
    assertThat(PasswordPolicy.check("iloveyou123456")).contains(Violation.COMMON);
    assertThat(PasswordPolicy.check("qwertyuiop")).contains(Violation.TOO_SHORT);
  }

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 messages never echo the password")
  void messages() {
    for (Violation v : Violation.values()) {
      assertThat(PasswordPolicy.message(v)).startsWith("password");
    }
  }
}
