package eg.masroof.adapters.in.rest;

import eg.masroof.domain.auth.FailureWindowLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * REQ-SEC-051: 60 requests per 60 s per client IP on every unauthenticated route. The container's
 * own loopback health probe is not counted. Over the limit: 429 Problem+JSON with Retry-After.
 */
final class PublicRateLimitFilter extends OncePerRequestFilter {

  private final FailureWindowLimiter limiter =
      new FailureWindowLimiter(60, Duration.ofSeconds(60), 10_000);
  private final ClientIp clientIp;
  private final Clock clock;

  PublicRateLimitFilter(ClientIp clientIp, Clock clock) {
    this.clientIp = clientIp;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!AuthenticationFilter.isPublic(request) || ClientIp.isLoopbackProbe(request)) {
      chain.doFilter(request, response);
      return;
    }
    String key = clientIp.of(request);
    Instant now = clock.instant();
    Optional<Duration> wait = limiter.blockedFor(key, now);
    if (wait.isPresent()) {
      long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
      ProblemResponses.tooManyRequests(request, response, seconds);
      return;
    }
    limiter.recordFailure(key, now); // counts requests, not failures
    chain.doFilter(request, response);
  }
}
