package eg.masroof.application.auth;

import eg.masroof.application.auth.AuthException.Kind;
import eg.masroof.application.auth.RefreshTokenStore.Outcome;
import eg.masroof.domain.auth.FailureWindowLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Use cases: login, refresh with rotation and reuse detection, logout (REQ-SEC-001/005/006/007).
 */
@Service
public class AuthService {

  private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
  private static final Duration REFRESH_TTL = Duration.ofDays(30);
  private static final int MAX_TOKEN_CHARS = 128;
  private static final int MAX_FAILURES = 10;
  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final int MAX_KEYS = 10_000;

  private final AccountStore accounts;
  private final RefreshTokenStore refreshTokens;
  private final PasswordHasher hasher;
  private final AccessTokens accessTokens;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final FailureWindowLimiter emailLimiter =
      new FailureWindowLimiter(MAX_FAILURES, WINDOW, MAX_KEYS);
  private final FailureWindowLimiter ipLimiter =
      new FailureWindowLimiter(MAX_FAILURES, WINDOW, MAX_KEYS);
  private final AtomicLong blocked = new AtomicLong();

  AuthService(
      AccountStore accounts,
      RefreshTokenStore refreshTokens,
      PasswordHasher hasher,
      AccessTokens accessTokens,
      Clock clock) {
    this.accounts = accounts;
    this.refreshTokens = refreshTokens;
    this.hasher = hasher;
    this.accessTokens = accessTokens;
    this.clock = clock;
  }

  /** Counter behind the {@code auth_login_blocked_total} metric. */
  public long blockedTotal() {
    return blocked.get();
  }

  /** Checks the credentials and opens a session. */
  public Session login(String email, String password, String clientIp) {
    Instant now = clock.instant();
    String emailKey = email.strip().toLowerCase(Locale.ROOT);
    String ipKey = "ip:" + clientIp;
    Optional<Duration> wait =
        longest(emailLimiter.blockedFor(emailKey, now), ipLimiter.blockedFor(ipKey, now));
    if (wait.isPresent()) {
      blocked.incrementAndGet();
      LOG.warn("auth_login_blocked_total={}", blocked.get());
      throw new AuthException(Kind.RATE_LIMITED, wait.get());
    }
    Optional<Account> found = accounts.findByEmail(emailKey);
    // Hash comparison runs on both paths so timing does not reveal whether the e-mail exists.
    boolean ok =
        hasher.matches(password, found.map(Account::passwordHash).orElseGet(hasher::dummyHash));
    if (found.isEmpty() || !ok) {
      emailLimiter.recordFailure(emailKey, now);
      ipLimiter.recordFailure(ipKey, now);
      throw new AuthException(Kind.INVALID_CREDENTIALS);
    }
    emailLimiter.reset(emailKey);
    Account account = found.get();
    String refresh = newRefreshToken();
    refreshTokens.insertNewFamily(account.userId(), sha256(refresh), now.plus(REFRESH_TTL));
    return session(account, refresh, now);
  }

  /** Rotates a refresh token. */
  public Session refresh(String presented) {
    Instant now = clock.instant();
    if (presented.length() > MAX_TOKEN_CHARS) {
      throw new AuthException(Kind.REFRESH_INVALID);
    }
    String next = newRefreshToken();
    Outcome outcome =
        refreshTokens.rotate(sha256(presented), sha256(next), now.plus(REFRESH_TTL), now);
    if (outcome instanceof Outcome.Rotated rotated) {
      Account account =
          accounts
              .findByUserId(rotated.userId())
              .orElseThrow(() -> new AuthException(Kind.REFRESH_INVALID));
      return session(account, next, now);
    }
    throw new AuthException(
        outcome instanceof Outcome.Expired ? Kind.REFRESH_EXPIRED : Kind.REFRESH_INVALID);
  }

  /** Revokes one session (by its refresh token) or all sessions of the user. */
  public void logout(UUID userId, @Nullable String refreshToken, boolean allSessions) {
    Instant now = clock.instant();
    if (allSessions) {
      refreshTokens.revokeAll(userId, now);
    } else if (refreshToken != null && refreshToken.length() <= MAX_TOKEN_CHARS) {
      refreshTokens.revokeFamily(sha256(refreshToken), userId, now);
    }
  }

  private Session session(Account account, String refresh, Instant now) {
    return new Session(
        accessTokens.issue(account, now), refresh, accessTokens.ttl().toSeconds(), account);
  }

  private String newRefreshToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

  /** Lower-case hex SHA-256, the form refresh tokens are stored in. */
  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
