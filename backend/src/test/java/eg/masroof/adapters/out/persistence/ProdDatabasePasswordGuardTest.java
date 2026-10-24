package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Security fix loop (ASVS 13.3.1): profile prod must not start without a database password. */
class ProdDatabasePasswordGuardTest {

  private static final String PROPERTY = "spring.datasource.password";

  @Test
  @DisplayName("prod guard rejects a missing spring.datasource.password")
  void rejectsMissingPassword() {
    assertThatThrownBy(() -> new ProdDatabasePasswordGuard(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(PROPERTY)
        .hasMessageContaining("POSTGRES_PASSWORD");
  }

  @Test
  @DisplayName("prod guard rejects a blank spring.datasource.password")
  void rejectsBlankPassword() {
    MockEnvironment env = new MockEnvironment().withProperty(PROPERTY, "  ");

    assertThatThrownBy(() -> new ProdDatabasePasswordGuard(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(PROPERTY)
        .hasMessageContaining("POSTGRES_PASSWORD");
  }

  @Test
  @DisplayName("prod guard rejects an unresolved ${POSTGRES_PASSWORD} placeholder")
  void rejectsUnresolvedPlaceholder() {
    MockEnvironment env = new MockEnvironment().withProperty(PROPERTY, "${POSTGRES_PASSWORD}");

    assertThatThrownBy(() -> new ProdDatabasePasswordGuard(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(PROPERTY)
        .hasNoCause();
  }

  @Test
  @DisplayName("prod guard accepts a non-empty spring.datasource.password")
  void acceptsPassword() {
    MockEnvironment env = new MockEnvironment().withProperty(PROPERTY, "s3cret-test");

    assertThatCode(() -> new ProdDatabasePasswordGuard(env)).doesNotThrowAnyException();
  }
}
