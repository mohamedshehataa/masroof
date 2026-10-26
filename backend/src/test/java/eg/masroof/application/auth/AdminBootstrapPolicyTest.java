package eg.masroof.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class AdminBootstrapPolicyTest {

  private final List<String> created = new ArrayList<>();
  private boolean loginExists;

  private final AccountStore accounts =
      new AccountStore() {
        @Override
        public Optional<Account> findByEmail(String email) {
          return Optional.empty();
        }

        @Override
        public Optional<Account> findByUserId(UUID userId) {
          return Optional.empty();
        }

        @Override
        public boolean hasAnyLogin() {
          return loginExists;
        }

        @Override
        public boolean createFirstAdmin(String email, String displayName, String passwordHash) {
          created.add(email);
          return true;
        }
      };

  private final PasswordHasher hasher =
      new PasswordHasher() {
        @Override
        public String hash(String password) {
          return "h";
        }

        @Override
        public boolean matches(String password, String hash) {
          return false;
        }

        @Override
        public String dummyHash() {
          return "d";
        }
      };

  private final AdminBootstrap bootstrap = new AdminBootstrap(accounts, hasher);

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 the bootstrap admin refuses common, over-long and short passwords")
  void refusesBadPasswords() {
    for (String pw : new String[] {"password1234", "a".repeat(73), "short"}) {
      assertThatThrownBy(() -> bootstrap.run("owner@example.com", pw))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(pw);
    }
    assertThat(created).isEmpty();
    bootstrap.run("owner@example.com", "a long bootstrap phrase");
    assertThat(created).containsExactly("owner@example.com");
  }

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName(
      "REQ-SEC-062 once a login exists the variables are ignored: no throw, nothing created")
  void ignoredWhenLoginExists() {
    loginExists = true;
    bootstrap.run("owner@example.com", "password1234");
    bootstrap.run("not-an-email", "short");
    assertThat(created).isEmpty();
  }
}
