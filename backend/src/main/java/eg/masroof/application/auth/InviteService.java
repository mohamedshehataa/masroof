package eg.masroof.application.auth;

import eg.masroof.application.auth.AuthException.Kind;
import eg.masroof.application.auth.InviteStore.AcceptResult;
import eg.masroof.application.auth.InviteStore.CreateResult;
import eg.masroof.application.auth.InviteStore.InviteView;
import eg.masroof.application.auth.InviteStore.MemberView;
import eg.masroof.application.auth.InviteStore.NewInvite;
import eg.masroof.domain.auth.FailureWindowLimiter;
import eg.masroof.domain.auth.InviteCode;
import eg.masroof.domain.auth.IpHasher;
import eg.masroof.domain.auth.PasswordPolicy;
import eg.masroof.domain.auth.Role;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Use cases: admin creates, lists and revokes invites; a holder of a code accepts it and becomes a
 * MEMBER of the inviter's household (REQ-SEC-060, REQ-SEC-061). The code is never logged or stored.
 */
@Service
public class InviteService {

  private static final Logger LOG = LoggerFactory.getLogger(InviteService.class);
  private static final int MAX_NAME = 80;
  private static final int MAX_FAILURES = 10;
  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final int MAX_KEYS = 10_000;

  /** A created invite: the plain code exists only in this value, once. */
  public record Created(UUID id, String code, String displayName, Instant expiresAt) {

    /** Never prints the code. */
    @Override
    public String toString() {
      return "Created[id=" + id + "]";
    }
  }

  private final InviteStore store;
  private final PasswordHasher hasher;
  private final AuthService auth;
  private final AuditLog audit;
  private final Clock clock;
  private final IpHasher ipHasher;
  private final Duration ttl;
  private final int maxPending;
  private final int maxMembers;
  private final SecureRandom random = new SecureRandom();
  private final FailureWindowLimiter ipLimiter =
      new FailureWindowLimiter(MAX_FAILURES, WINDOW, MAX_KEYS);
  private final FailureWindowLimiter codeLimiter =
      new FailureWindowLimiter(MAX_FAILURES, WINDOW, MAX_KEYS);

  @Autowired
  InviteService(
      InviteStore store,
      PasswordHasher hasher,
      AuthService auth,
      AuditLog audit,
      Clock clock,
      IpHasher ipHasher,
      @Value("${masroof.invite.ttl-days:7}") int ttlDays,
      @Value("${masroof.invite.max-pending:3}") int maxPending,
      @Value("${masroof.invite.max-members:2}") int maxMembers) {
    this.store = store;
    this.hasher = hasher;
    this.auth = auth;
    this.audit = audit;
    this.clock = clock;
    this.ipHasher = ipHasher;
    this.ttl = Duration.ofDays(ttlDays);
    this.maxPending = maxPending;
    this.maxMembers = maxMembers;
  }

  /** Creates an invite; admin only. */
  public Created create(
      Caller caller,
      String displayName,
      @Nullable String email,
      @Nullable String phoneE164,
      @Nullable String role,
      String clientIp) {
    requireAdmin(caller);
    String name = displayName.strip();
    if (name.isEmpty() || name.length() > MAX_NAME) {
      throw new InviteException(
          InviteException.Kind.INVALID_FIELD, "displayName is required", "displayName");
    }
    if (role != null && !"MEMBER".equals(role)) {
      throw new InviteException(
          InviteException.Kind.INVALID_FIELD, "Only MEMBER invites are supported", "role");
    }
    String mail = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
    if (mail != null && !validEmail(mail)) {
      throw new InviteException(InviteException.Kind.INVALID_FIELD, "email is not valid", "email");
    }
    Instant now = clock.instant();
    String code = InviteCode.generate(random);
    UUID id = UUID.randomUUID();
    CreateResult result =
        store.create(
            new NewInvite(
                id,
                caller.householdId(),
                caller.memberId(),
                InviteCode.hash(code),
                name,
                mail,
                phoneE164,
                now,
                now.plus(ttl)),
            maxPending,
            maxMembers,
            now);
    if (result == CreateResult.TOO_MANY_PENDING) {
      throw new InviteException(
          InviteException.Kind.CONFLICT, "Too many pending invites; revoke one first", null);
    }
    if (result == CreateResult.HOUSEHOLD_FULL) {
      throw new InviteException(InviteException.Kind.CONFLICT, "Household is full", null);
    }
    audit("INVITE_CREATED", caller.memberId(), clientIp);
    return new Created(id, code, name, now.plus(ttl));
  }

  /** Pending invites of the caller's household; admin only. */
  public List<InviteView> list(Caller caller) {
    requireAdmin(caller);
    return store.listActive(caller.householdId(), clock.instant());
  }

  /** Revokes a pending invite of the caller's household; admin only. */
  public void revoke(Caller caller, UUID inviteId, String clientIp) {
    requireAdmin(caller);
    InviteStore.RevokeResult result = store.revoke(caller.householdId(), inviteId, clock.instant());
    if (result == InviteStore.RevokeResult.REVOKED) {
      audit("INVITE_REVOKED", caller.memberId(), clientIp);
    } else if (result == InviteStore.RevokeResult.ACCEPTED) {
      throw new InviteException(InviteException.Kind.CONFLICT, "Invite already accepted", null);
    } else if (result == InviteStore.RevokeResult.NOT_FOUND) {
      throw new InviteException(InviteException.Kind.NOT_FOUND, "Invite not found", null);
    }
    // ALREADY_REVOKED: idempotent, nothing changed, so nothing is audited
  }

  /** Members of the caller's household. */
  public List<MemberView> members(Caller caller) {
    return store.members(caller.householdId());
  }

  /**
   * Accepts an invite. Every invalid, expired, used, revoked or e-mail-mismatched code gets the
   * same 404. A code that cannot be claimed is rejected by a cheap read before the BCrypt hash, so
   * floods of bad codes cannot burn CPU; with 256-bit codes the timing difference tells an attacker
   * nothing they can use.
   */
  public Session accept(
      String code, String email, String password, @Nullable String displayName, String clientIp) {
    Instant now = clock.instant();
    String presented = code.strip();
    String codeKey = "code:" + InviteCode.hash(presented);
    String ipKey = "ip:" + clientIp;
    Optional<Duration> wait =
        longest(ipLimiter.blockedFor(ipKey, now), codeLimiter.blockedFor(codeKey, now));
    if (wait.isPresent()) {
      throw new AuthException(Kind.RATE_LIMITED, wait.get());
    }
    String mail = email.strip().toLowerCase(Locale.ROOT);
    if (!validEmail(mail)) {
      throw new InviteException(InviteException.Kind.INVALID_FIELD, "email is not valid", "email");
    }
    Optional<PasswordPolicy.Violation> weak = PasswordPolicy.check(password);
    if (weak.isPresent()) {
      throw new InviteException(
          InviteException.Kind.INVALID_FIELD, PasswordPolicy.message(weak.get()), "password");
    }
    String name = displayName == null || displayName.isBlank() ? null : displayName.strip();
    if (name != null && name.length() > MAX_NAME) {
      throw new InviteException(
          InviteException.Kind.INVALID_FIELD, "displayName is too long", "displayName");
    }
    String codeHash = InviteCode.hash(presented);
    boolean plausible = !presented.isEmpty() && presented.length() <= InviteCode.MAX_LENGTH;
    AcceptResult result =
        plausible && store.claimable(codeHash, now, mail)
            ? store.accept(codeHash, now, mail, name, hasher.hash(password), maxMembers)
            : new AcceptResult.Invalid();
    if (result instanceof AcceptResult.Accepted accepted) {
      codeLimiter.reset(codeKey);
      audit("INVITE_ACCEPTED", accepted.account().memberId(), clientIp);
      return auth.startSession(accepted.account());
    }
    ipLimiter.recordFailure(ipKey, now);
    // A taken e-mail or a full household needs a valid code, so it is not a guess at the code.
    if (result instanceof AcceptResult.Invalid) {
      codeLimiter.recordFailure(codeKey, now);
    }
    audit("INVITE_FAILED", null, clientIp);
    if (result instanceof AcceptResult.EmailTaken) {
      throw new InviteException(
          InviteException.Kind.CONFLICT, "This e-mail cannot be used", "email");
    }
    if (result instanceof AcceptResult.Full) {
      throw new InviteException(InviteException.Kind.CONFLICT, "Household is full", null);
    }
    throw new InviteException(InviteException.Kind.NOT_FOUND, "Invite not found", null);
  }

  private static void requireAdmin(Caller caller) {
    if (caller.role() != Role.ADMIN) {
      throw new InviteException(InviteException.Kind.FORBIDDEN, "Admin only", null);
    }
  }

  private static boolean validEmail(String mail) {
    int at = mail.indexOf('@');
    return at >= 1
        && at == mail.lastIndexOf('@')
        && at < mail.length() - 3
        && mail.length() <= 254
        && mail.indexOf(' ') < 0;
  }

  private void audit(String action, @Nullable UUID memberId, String clientIp) {
    try {
      audit.record(action, memberId, ipHasher.hash(clientIp));
    } catch (RuntimeException e) {
      LOG.warn("audit write failed for {}", action);
    }
  }

  @SafeVarargs
  private static Optional<Duration> longest(Optional<Duration>... waits) {
    Optional<Duration> best = Optional.empty();
    for (Optional<Duration> w : waits) {
      if (w.isPresent() && (best.isEmpty() || w.get().compareTo(best.get()) > 0)) {
        best = w;
      }
    }
    return best;
  }
}
