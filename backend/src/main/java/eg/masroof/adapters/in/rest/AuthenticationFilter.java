package eg.masroof.adapters.in.rest;

import eg.masroof.application.auth.AccessTokens;
import eg.masroof.application.auth.AccessTokens.Claims;
import eg.masroof.application.auth.Account;
import eg.masroof.application.auth.AccountStore;
import eg.masroof.application.auth.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token authentication (REQ-SEC-012). Public paths pass untouched. Every other request needs
 * a valid access token whose {@code hid} still matches the member's household; a token that is
 * present but wrong is always 401, even with the dev single-household switch. Without any token,
 * only that switch lets a request through (the household then comes from the dev provider).
 */
final class AuthenticationFilter extends OncePerRequestFilter {

  /** Request attribute holding the {@link Caller}. */
  static final String CALLER = AuthenticationFilter.class.getName() + ".caller";

  private static final Set<String> PUBLIC =
      Set.of(
          "/health",
          "/auth/login",
          "/auth/refresh",
          "/auth/invite/accept",
          "/api/app/android/latest");

  private final AccessTokens tokens;
  private final AccountStore accounts;
  private final Clock clock;
  private final boolean devSingleHousehold;

  AuthenticationFilter(
      AccessTokens tokens, AccountStore accounts, Clock clock, boolean devSingleHousehold) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.clock = clock;
    this.devSingleHousehold = devSingleHousehold;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (PUBLIC.contains(request.getRequestURI()) || "OPTIONS".equals(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null) {
      if (devSingleHousehold) {
        chain.doFilter(request, response);
      } else {
        ProblemResponses.unauthorized(request, response, null);
      }
      return;
    }
    Optional<Caller> caller = authenticate(header);
    if (caller.isEmpty()) {
      ProblemResponses.unauthorized(request, response, "invalid_token");
      return;
    }
    request.setAttribute(CALLER, caller.get());
    chain.doFilter(request, response);
  }

  private Optional<Caller> authenticate(String header) {
    if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    Optional<Claims> claims = tokens.verify(header.substring(7).strip(), clock.instant());
    if (claims.isEmpty()) {
      return Optional.empty();
    }
    Optional<Account> account = accounts.findByUserId(claims.get().userId());
    // The token's household must still be the member's household (member moved or removed -> 401).
    if (account.isEmpty() || !account.get().householdId().equals(claims.get().householdId())) {
      return Optional.empty();
    }
    return Optional.of(Caller.of(account.get()));
  }
}
