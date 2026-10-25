package eg.masroof.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import eg.masroof.application.auth.AuthException.Kind;
import eg.masroof.application.auth.RefreshTokenStore.Outcome;
import eg.masroof.domain.auth.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

  private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");

  private static final class MutableClock extends Clock {
    Instant now = T0;

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MutableClock clock = new MutableClock();
  private final Account account =
      new Account(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "EGP",
          "M",
          Role.ADMIN,
          "hash-ok");
  private final List<String> audited = new ArrayList<>();
  private final List<@Nullable UUID> auditedMembers = new ArrayList<>();
  private final List<String> auditedHashes = new ArrayList<>();
  private final List<String> stored = new ArrayList<>();
  private final List<String> revoked = new ArrayList<>();
  private Outcome next = new Outcome.Invalid();
  private int hashChecks;

  private final AccountStore accounts =
      new AccountStore() {
        @Override
        public Optional<Account> findByEmail(String email) {
          return email.equals("m@example.com") ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<Account> findByUserId(UUID userId) {
          return userId.equals(account.userId()) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public boolean createFirstAdmin(String email, String displayName, String passwordHash) {
          return false;
        }
      };

  private final RefreshTokenStore tokens =
      new RefreshTokenStore() {
        @Override
        public void insertNewFamily(UUID userId, String tokenHash, Instant expiresAt) {
          stored.add(tokenHash + "@" + expiresAt);
        }

        @Override
        public Outcome rotate(String oldHash, String newHash, Instant newExpiresAt, Instant now) {
          stored.add(newHash + "@" + newExpiresAt);
          return next;
        }

        @Override
        public void revokeFamily(String tokenHash, @Nullable UUID userId, Instant now) {
          revoked.add("family:" + tokenHash);
        }

        @Override
        public void revokeAll(UUID userId, Instant now) {
          revoked.add("all:" + userId);
        }

        @Override
        public void revokeAllOfOwner(String tokenHash, Instant now) {
          revoked.add("owner:" + tokenHash);
        }
      };

  private final PasswordHasher hasher =
      new PasswordHasher() {
        @Override
        public String hash(String password) {
          return "h:" + password;
        }

        @Override
        public boolean matches(String password, String hash) {
          hashChecks++;
          return hash.equals("hash-ok") && password.equals("right password");
        }

        @Override
        public String dummyHash() {
          return "dummy";
        }
      };

  private final AccessTokens access =
      new AccessTokens() {
        @Override
        public Duration ttl() {
          return Duration.ofMinutes(15);
        }

        @Override
        public String issue(Account a, Instant now) {
          return "jwt-" + now.getEpochSecond();
        }

        @Override
        public Optional<Claims> verify(String token, Instant now) {
          return Optional.empty();
        }
      };

  private final AuditLog audit =
      (action, memberId, ipHash) -> {
        audited.add(action);
        auditedMembers.add(memberId);
        auditedHashes.add(ipHash);
      };

  private final AuthService service =
      new AuthService(accounts, tokens, hasher, access, audit, clock);

  private AuthException failure(Runnable r) {
    return catchThrowableOfType(AuthException.class, r::run);
  }

  @Test
  @Tag("REQ-SEC-001")
  @DisplayName("REQ-SEC-001 a good login returns a 15 min session and stores a 30 day refresh hash")
  void loginOk() {
    Session s = service.login(" M@Example.com ", "right password", "198.51.100.1");
    assertThat(s.accessToken()).isEqualTo("jwt-" + T0.getEpochSecond());
    assertThat(s.expiresInSeconds()).isEqualTo(900);
    assertThat(s.refreshToken()).hasSize(43); // 32 bytes, base64url, no padding
    assertThat(stored).hasSize(1);
    String[] row = stored.get(0).split("@", -1);
    assertThat(row[0]).hasSize(64).matches("[0-9a-f]+").isNotEqualTo(s.refreshToken());
    assertThat(row[1]).isEqualTo(T0.plus(Duration.ofDays(30)).toString());
    assertThat(s.toString()).doesNotContain(s.refreshToken()).doesNotContain("hash-ok");
    assertThat(audited).isEmpty();
  }

  @Test
  @Tag("REQ-SEC-001")
  @DisplayName("REQ-SEC-001 unknown e-mail and wrong password both check a hash and fail alike")
  void loginFailsAlike() {
    AuthException unknown = failure(() -> service.login("nobody@example.com", "x", "198.51.100.2"));
    AuthException wrong = failure(() -> service.login("m@example.com", "wrong", "198.51.100.3"));
    assertThat(unknown.kind()).isEqualTo(Kind.INVALID_CREDENTIALS);
    assertThat(wrong.kind()).isEqualTo(Kind.INVALID_CREDENTIALS);
    assertThat(unknown.title()).isEqualTo("Invalid credentials").isEqualTo(wrong.title());
    assertThat(hashChecks).isEqualTo(2);
    assertThat(audited).containsExactly("LOGIN_FAILED", "LOGIN_FAILED");
    assertThat(auditedMembers).containsExactly(null, account.memberId());
    assertThat(auditedHashes.get(0)).hasSize(64).doesNotContain("198.51.100");
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 the 11th attempt per e-mail is blocked, even with the right password")
  void emailLockout() {
    for (int i = 0; i < 10; i++) {
      int n = i;
      assertThat(
              failure(() -> service.login("m@example.com", "wrong", "198.51.100." + (10 + n)))
                  .kind())
          .isEqualTo(Kind.INVALID_CREDENTIALS);
    }
    clock.now = T0.plusSeconds(60);
    AuthException blocked =
        failure(() -> service.login("m@example.com", "right password", "198.51.100.99"));
    assertThat(blocked.kind()).isEqualTo(Kind.RATE_LIMITED);
    assertThat(blocked.retryAfter()).isEqualTo(Duration.ofMinutes(14));
    assertThat(service.blockedTotal()).isEqualTo(1);
    assertThat(audited).endsWith("LOGIN_BLOCKED");
    // after the window the right password works again
    clock.now = T0.plus(Duration.ofMinutes(15));
    assertThat(service.login("m@example.com", "right password", "198.51.100.99").account())
        .isEqualTo(account);
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 LOGIN_BLOCKED is audited once per window per key")
  void blockedAuditedOncePerWindow() {
    for (int i = 0; i < 10; i++) {
      failure(() -> service.login("m@example.com", "wrong", "198.51.100.5"));
    }
    clock.now = T0.plusSeconds(60);
    for (int i = 0; i < 5; i++) {
      failure(() -> service.login("m@example.com", "wrong", "198.51.100.5"));
    }
    assertThat(service.blockedTotal()).isEqualTo(5);
    assertThat(audited.stream().filter("LOGIN_BLOCKED"::equals).count()).isEqualTo(1);
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 the 10th failure is still a plain 401; the IP key blocks other e-mails")
  void ipLockout() {
    for (int i = 0; i < 9; i++) {
      failure(() -> service.login("x@example.com", "wrong", "198.51.100.7"));
    }
    assertThat(failure(() -> service.login("y@example.com", "wrong", "198.51.100.7")).kind())
        .isEqualTo(Kind.INVALID_CREDENTIALS);
    AuthException blocked = failure(() -> service.login("z@example.com", "wrong", "198.51.100.7"));
    assertThat(blocked.kind()).isEqualTo(Kind.RATE_LIMITED);
    assertThat(failure(() -> service.login("z@example.com", "wrong", "198.51.100.8")).kind())
        .isEqualTo(Kind.INVALID_CREDENTIALS);
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 a good login resets the e-mail counter")
  void successResets() {
    for (int i = 0; i < 9; i++) {
      int n = i;
      failure(() -> service.login("m@example.com", "wrong", "198.51.100." + (20 + n)));
    }
    service.login("m@example.com", "right password", "198.51.100.60");
    for (int i = 0; i < 9; i++) {
      int n = i;
      assertThat(
              failure(() -> service.login("m@example.com", "wrong", "198.51.100." + (70 + n)))
                  .kind())
          .isEqualTo(Kind.INVALID_CREDENTIALS);
    }
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 a failing audit write never changes the answer")
  void auditFailureIgnored() {
    AuthService fragile =
        new AuthService(
            accounts,
            tokens,
            hasher,
            access,
            (a, m, h) -> {
              throw new IllegalStateException("db down");
            },
            clock);
    assertThat(
            catchThrowableOfType(
                    AuthException.class, () -> fragile.login("m@example.com", "no", "1.1.1.1"))
                .kind())
        .isEqualTo(Kind.INVALID_CREDENTIALS);
  }

  @Test
  @Tag("REQ-SEC-005")
  @DisplayName("REQ-SEC-005 refresh maps each store outcome")
  void refreshOutcomes() {
    next = new Outcome.Rotated(account.userId());
    Session s = service.refresh("some-token");
    assertThat(s.account()).isEqualTo(account);
    assertThat(stored.get(0)).endsWith("@" + T0.plus(Duration.ofDays(30)));
    next = new Outcome.Expired();
    assertThat(failure(() -> service.refresh("t")).kind()).isEqualTo(Kind.REFRESH_EXPIRED);
    assertThat(failure(() -> service.refresh("t")).title()).isEqualTo("Refresh token expired");
    next = new Outcome.Reuse();
    assertThat(failure(() -> service.refresh("t")).kind()).isEqualTo(Kind.REFRESH_INVALID);
    next = new Outcome.Invalid();
    assertThat(failure(() -> service.refresh("t")).kind()).isEqualTo(Kind.REFRESH_INVALID);
    next = new Outcome.Rotated(UUID.randomUUID()); // user deleted meanwhile
    assertThat(failure(() -> service.refresh("t")).kind()).isEqualTo(Kind.REFRESH_INVALID);
    int before = stored.size();
    assertThat(failure(() -> service.refresh("x".repeat(129))).kind())
        .isEqualTo(Kind.REFRESH_INVALID);
    assertThat(stored).hasSize(before); // oversized tokens never reach the store
    next = new Outcome.Rotated(account.userId());
    assertThat(service.refresh("x".repeat(128)).account()).isEqualTo(account);
  }

  @Test
  @Tag("REQ-SEC-006")
  @DisplayName("REQ-SEC-006 logout revokes one family, all sessions, or nothing")
  void logout() {
    service.logout(account.userId(), "tok", false);
    assertThat(revoked).hasSize(1);
    assertThat(revoked.get(0)).startsWith("family:").hasSize(7 + 64);
    service.logout(account.userId(), "tok", true);
    assertThat(revoked).endsWith("all:" + account.userId());
    int n = revoked.size();
    service.logout(account.userId(), null, false);
    service.logout(account.userId(), "x".repeat(129), false);
    assertThat(revoked).hasSize(n);
    service.logout(account.userId(), "x".repeat(128), false);
    assertThat(revoked).hasSize(n + 1);
  }

  @Test
  @Tag("REQ-SEC-001")
  @DisplayName("REQ-SEC-001 refresh tokens are random and nothing sensitive is printed")
  void tokensRandomAndRedacted() {
    Session a = service.login("m@example.com", "right password", "198.51.100.1");
    Session b = service.login("m@example.com", "right password", "198.51.100.1");
    assertThat(a.refreshToken()).isNotEqualTo(b.refreshToken());
    assertThat(a.refreshToken()).isNotEqualTo("A".repeat(43));
    assertThat(a.toString()).startsWith("Session[account=Account[userId=").doesNotContain("jwt-");
    assertThat(account.toString()).contains(account.userId().toString()).doesNotContain("hash-ok");
  }

  @Test
  @Tag("REQ-SEC-006")
  @DisplayName("REQ-SEC-006 without a bearer the refresh token alone revokes its family or owner")
  void logoutWithoutBearer() {
    service.logout(null, "tok", false);
    assertThat(revoked).hasSize(1);
    assertThat(revoked.get(0)).startsWith("family:");
    service.logout(null, "tok", true);
    assertThat(revoked.get(1)).startsWith("owner:").hasSize(6 + 64);
    service.logout(null, null, true);
    service.logout(null, null, false);
    service.logout(null, "x".repeat(129), true);
    service.logout(null, "x".repeat(129), false);
    assertThat(revoked).hasSize(2);
    service.logout(null, "x".repeat(128), true);
    assertThat(revoked).hasSize(3);
  }
}
