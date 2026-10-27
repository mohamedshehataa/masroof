package eg.masroof.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import eg.masroof.application.auth.AuthException.Kind;
import eg.masroof.application.auth.InviteStore.AcceptResult;
import eg.masroof.application.auth.InviteStore.CreateResult;
import eg.masroof.application.auth.InviteStore.InviteView;
import eg.masroof.application.auth.InviteStore.MemberView;
import eg.masroof.application.auth.InviteStore.NewInvite;
import eg.masroof.domain.auth.InviteCode;
import eg.masroof.domain.auth.IpHasher;
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

class InviteServiceTest {

  private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
  private static final String PASSWORD = "twelve chars ok";

  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
  private final UUID household = UUID.randomUUID();
  private final Caller admin =
      new Caller(UUID.randomUUID(), UUID.randomUUID(), household, "EGP", Role.ADMIN);
  private final Caller member =
      new Caller(UUID.randomUUID(), UUID.randomUUID(), household, "EGP", Role.MEMBER);
  private final List<String> audited = new ArrayList<>();
  private final List<String> auditedHashes = new ArrayList<>();
  private final List<NewInvite> created = new ArrayList<>();
  private final List<String> acceptCalls = new ArrayList<>();
  private int hashes;
  private CreateResult createResult = CreateResult.CREATED;
  private AcceptResult acceptResult = new AcceptResult.Invalid();
  private InviteStore.RevokeResult revokeResult = InviteStore.RevokeResult.REVOKED;
  private boolean claimable = true;

  private final InviteStore store =
      new InviteStore() {
        @Override
        public CreateResult create(NewInvite invite, int maxPending, int maxMembers, Instant now) {
          created.add(invite);
          return createResult;
        }

        @Override
        public List<InviteView> listActive(UUID householdId, Instant now) {
          return List.of();
        }

        @Override
        public RevokeResult revoke(UUID householdId, UUID inviteId, Instant now) {
          return revokeResult;
        }

        @Override
        public boolean claimable(String codeHash, Instant now, String email) {
          return claimable;
        }

        @Override
        public AcceptResult accept(
            String codeHash,
            Instant now,
            String email,
            @Nullable String displayName,
            String passwordHash,
            int maxMembers) {
          acceptCalls.add(codeHash);
          return acceptResult;
        }

        @Override
        public List<MemberView> members(UUID householdId) {
          return List.of();
        }
      };

  private final PasswordHasher hasher =
      new PasswordHasher() {
        @Override
        public String hash(String password) {
          hashes++;
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

  private final AccessTokens access =
      new AccessTokens() {
        @Override
        public Duration ttl() {
          return Duration.ofMinutes(15);
        }

        @Override
        public String issue(Account a, Instant now) {
          return "jwt";
        }

        @Override
        public Optional<Claims> verify(String token, Instant now) {
          return Optional.empty();
        }
      };

  private final AuditLog audit =
      (action, memberId, ipHash) -> {
        audited.add(action);
        auditedHashes.add(ipHash);
      };

  private final RefreshTokenStore tokens =
      new RefreshTokenStore() {
        @Override
        public void insertNewFamily(UUID userId, String tokenHash, Instant expiresAt) {}

        @Override
        public Outcome rotate(
            String oldHash,
            String newHash,
            Instant newExpiresAt,
            Instant now,
            Duration reuseGrace,
            int maxGraceReissues) {
          return new Outcome.Invalid();
        }

        @Override
        public void revokeFamily(String tokenHash, @Nullable UUID userId, Instant now) {}

        @Override
        public void revokeAll(UUID userId, Instant now) {}

        @Override
        public void revokeAllOfOwner(String tokenHash, Instant now) {}
      };

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
        public boolean createFirstAdmin(String email, String displayName, String passwordHash) {
          return false;
        }
      };

  private final IpHasher ipHasher = new IpHasher(new byte[32]);
  private final AuthService auth =
      new AuthService(accounts, tokens, hasher, access, audit, clock, ipHasher);
  private final InviteService service =
      new InviteService(store, hasher, auth, audit, clock, ipHasher, 7, 3, 2);

  private <T extends RuntimeException> T failure(Class<T> type, Runnable r) {
    return catchThrowableOfType(type, r::run);
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 an admin gets a code once; only its hash is stored; expires in 7 days")
  void createStoresOnlyTheHash() {
    InviteService.Created c =
        service.create(admin, " Mai ", "Mai@Example.com", "+201000000000", null, "198.51.100.1");
    assertThat(created).hasSize(1);
    NewInvite stored = created.get(0);
    assertThat(stored.codeHash()).isEqualTo(InviteCode.hash(c.code())).isNotEqualTo(c.code());
    assertThat(stored.displayName()).isEqualTo("Mai");
    assertThat(stored.email()).isEqualTo("mai@example.com");
    assertThat(stored.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(7)));
    assertThat(c.expiresAt()).isEqualTo(stored.expiresAt());
    assertThat(c.toString()).doesNotContain(c.code());
    assertThat(stored.toString()).doesNotContain(stored.codeHash());
    assertThat(audited).containsExactly("INVITE_CREATED");
    assertThat(auditedHashes.get(0)).hasSize(64).doesNotContain("198.51.100.1");
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 members cannot create, list or revoke invites (403)")
  void membersAreForbidden() {
    assertThat(
            failure(
                    InviteException.class,
                    () -> service.create(member, "Mai", null, null, null, "198.51.100.1"))
                .kind())
        .isEqualTo(InviteException.Kind.FORBIDDEN);
    assertThat(failure(InviteException.class, () -> service.list(member)).kind())
        .isEqualTo(InviteException.Kind.FORBIDDEN);
    assertThat(
            failure(
                    InviteException.class,
                    () -> service.revoke(member, UUID.randomUUID(), "198.51.100.1"))
                .kind())
        .isEqualTo(InviteException.Kind.FORBIDDEN);
    assertThat(created).isEmpty();
    assertThat(audited).isEmpty();
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 limits map to 409 and other households' invites to not found")
  void limitsAndRevoke() {
    createResult = CreateResult.TOO_MANY_PENDING;
    assertThat(
            failure(
                    InviteException.class,
                    () -> service.create(admin, "Mai", null, null, null, "198.51.100.1"))
                .kind())
        .isEqualTo(InviteException.Kind.CONFLICT);
    createResult = CreateResult.HOUSEHOLD_FULL;
    assertThat(
            failure(
                    InviteException.class,
                    () -> service.create(admin, "Mai", null, null, null, "198.51.100.1"))
                .getMessage())
        .isEqualTo("Household is full");
    revokeResult = InviteStore.RevokeResult.NOT_FOUND;
    assertThat(
            failure(
                    InviteException.class,
                    () -> service.revoke(admin, UUID.randomUUID(), "198.51.100.1"))
                .kind())
        .isEqualTo(InviteException.Kind.NOT_FOUND);
    assertThat(audited).isEmpty();
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 only MEMBER invites and valid e-mails are accepted")
  void validation() {
    InviteException role =
        failure(
            InviteException.class,
            () -> service.create(admin, "Mai", null, null, "ADMIN", "198.51.100.1"));
    assertThat(role.field()).isEqualTo("role");
    InviteException mail =
        failure(
            InviteException.class,
            () -> service.create(admin, "Mai", "nope", null, null, "198.51.100.1"));
    assertThat(mail.field()).isEqualTo("email");
    InviteException name =
        failure(
            InviteException.class, () -> service.create(admin, "  ", null, null, null, "1.1.1.1"));
    assertThat(name.field()).isEqualTo("displayName");
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 a short password is a 400 on field password and hits no store")
  void passwordPolicy() {
    InviteException e =
        failure(
            InviteException.class,
            () -> service.accept("code", "m@example.com", "11 chars!!!", null, "198.51.100.1"));
    assertThat(e.field()).isEqualTo("password");
    assertThat(e.getMessage()).doesNotContain("11 chars!!!");
    assertThat(acceptCalls).isEmpty();
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 an unusable code is one generic not-found and costs no BCrypt")
  void invalidCodeIsGeneric() {
    claimable = false;
    InviteException e =
        failure(
            InviteException.class,
            () -> service.accept("whatever", "m@example.com", PASSWORD, null, "198.51.100.1"));
    assertThat(e.kind()).isEqualTo(InviteException.Kind.NOT_FOUND);
    assertThat(e.getMessage()).doesNotContain("whatever");
    assertThat(hashes).isZero(); // no BCrypt for a code that cannot be claimed
    assertThat(acceptCalls).isEmpty();
    assertThat(audited).containsExactly("INVITE_FAILED");
    // an oversize code never reaches the store but fails the same way
    InviteException big =
        failure(
            InviteException.class,
            () -> service.accept("x".repeat(500), "m@example.com", PASSWORD, null, "198.51.100.2"));
    assertThat(big.kind()).isEqualTo(InviteException.Kind.NOT_FOUND);
    assertThat(acceptCalls).isEmpty();
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 a valid code returns a session for the new member and audits it")
  void acceptOk() {
    Account account =
        new Account(
            UUID.randomUUID(), UUID.randomUUID(), household, "EGP", "Mai", Role.MEMBER, "h");
    acceptResult = new AcceptResult.Accepted(account);
    Session s = service.accept(" code ", " M@Example.com ", PASSWORD, null, "198.51.100.1");
    assertThat(s.account().role()).isEqualTo(Role.MEMBER);
    assertThat(s.accessToken()).isEqualTo("jwt");
    assertThat(acceptCalls).containsExactly(InviteCode.hash("code"));
    assertThat(hashes).isEqualTo(1);
    assertThat(audited).containsExactly("INVITE_ACCEPTED");
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 e-mail taken and full household are 409 and still counted as failures")
  void conflicts() {
    acceptResult = new AcceptResult.EmailTaken();
    assertThat(
            failure(
                    InviteException.class,
                    () -> service.accept("c", "m@example.com", PASSWORD, null, "198.51.100.1"))
                .kind())
        .isEqualTo(InviteException.Kind.CONFLICT);
    acceptResult = new AcceptResult.Full();
    assertThat(
            failure(
                    InviteException.class,
                    () -> service.accept("c", "m@example.com", PASSWORD, null, "198.51.100.1"))
                .getMessage())
        .isEqualTo("Household is full");
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 ten failures from one IP block that IP; ten on one code block the code")
  void rateLimited() {
    for (int i = 0; i < 10; i++) {
      String code = "guess-" + i;
      failure(
          InviteException.class,
          () -> service.accept(code, "m@example.com", PASSWORD, null, "198.51.100.9"));
    }
    AuthException ip =
        failure(
            AuthException.class,
            () -> service.accept("another", "m@example.com", PASSWORD, null, "198.51.100.9"));
    assertThat(ip.kind()).isEqualTo(Kind.RATE_LIMITED);
    assertThat(ip.retryAfter()).isNotNull();
    int calls = acceptCalls.size();

    for (int i = 0; i < 10; i++) {
      String ipAddr = "203.0.113." + i;
      failure(
          InviteException.class,
          () -> service.accept("same-code", "m@example.com", PASSWORD, null, ipAddr));
    }
    AuthException code =
        failure(
            AuthException.class,
            () -> service.accept("same-code", "m@example.com", PASSWORD, null, "203.0.113.200"));
    assertThat(code.kind()).isEqualTo(Kind.RATE_LIMITED);
    assertThat(acceptCalls).hasSize(calls + 10);
  }

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 73 bytes, 64 Arabic characters and common passwords are 400, not 500")
  void passwordPolicyEdges() {
    for (String pw : List.of("a".repeat(73), "ب".repeat(64), "password1234", "Password1234!")) {
      InviteException e =
          failure(
              InviteException.class,
              () -> service.accept("code", "m@example.com", pw, null, "198.51.100.1"));
      assertThat(e.kind()).isEqualTo(InviteException.Kind.INVALID_FIELD);
      assertThat(e.field()).isEqualTo("password");
      assertThat(e.getMessage()).doesNotContain(pw);
    }
    assertThat(hashes).isZero();
    assertThat(acceptCalls).isEmpty();
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 revoking an accepted invite is 409; an already revoked one is silent")
  void revokeStates() {
    revokeResult = InviteStore.RevokeResult.ACCEPTED;
    InviteException accepted =
        failure(
            InviteException.class, () -> service.revoke(admin, UUID.randomUUID(), "198.51.100.1"));
    assertThat(accepted.kind()).isEqualTo(InviteException.Kind.CONFLICT);
    assertThat(accepted.getMessage()).isEqualTo("Invite already accepted");
    revokeResult = InviteStore.RevokeResult.ALREADY_REVOKED;
    service.revoke(admin, UUID.randomUUID(), "198.51.100.1");
    assertThat(audited).isEmpty();
    revokeResult = InviteStore.RevokeResult.REVOKED;
    service.revoke(admin, UUID.randomUUID(), "198.51.100.1");
    assertThat(audited).containsExactly("INVITE_REVOKED");
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 a taken e-mail with a valid code does not count against that code")
  void takenEmailDoesNotBlockTheCode() {
    acceptResult = new AcceptResult.EmailTaken();
    for (int i = 0; i < 12; i++) {
      String ipAddr = "203.0.113." + i;
      InviteException e =
          failure(
              InviteException.class,
              () -> service.accept("valid-code", "m@example.com", PASSWORD, null, ipAddr));
      assertThat(e.kind()).isEqualTo(InviteException.Kind.CONFLICT);
    }
    acceptResult =
        new AcceptResult.Accepted(
            new Account(
                UUID.randomUUID(), UUID.randomUUID(), household, "EGP", "Mai", Role.MEMBER, "h"));
    assertThat(
            service
                .accept("valid-code", "new@example.com", PASSWORD, null, "203.0.113.99")
                .account()
                .role())
        .isEqualTo(Role.MEMBER);
  }
}
