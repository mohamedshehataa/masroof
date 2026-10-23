package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Security fix loop (ASVS 13.3.1): profile prod must not start without a database password. */
class ProdDatabasePasswordGuardTest {

  @Test
  @DisplayName("prod guard rejects a missing POSTGRES_PASSWORD")
  void rejectsMissingPassword() {
    assertThatThrownBy(() -> new ProdDatabasePasswordGuard(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("POSTGRES_PASSWORD");
  }

  @Test
  @DisplayName("prod guard rejects a blank POSTGRES_PASSWORD")
  void rejectsBlankPassword() {
    MockEnvironment env = new MockEnvironment().withProperty("POSTGRES_PASSWORD", "  ");

    assertThatThrownBy(() -> new ProdDatabasePasswordGuard(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("POSTGRES_PASSWORD");
  }

  @Test
  @DisplayName("prod guard accepts a non-empty POSTGRES_PASSWORD")
  void acceptsPassword() {
    MockEnvironment env = new MockEnvironment().withProperty("POSTGRES_PASSWORD", "s3cret-test");

    assertThatCode(() -> new ProdDatabasePasswordGuard(env)).doesNotThrowAnyException();
  }
}
