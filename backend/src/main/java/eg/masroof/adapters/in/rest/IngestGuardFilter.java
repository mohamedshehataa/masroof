package eg.masroof.adapters.in.rest;

import eg.masroof.application.auth.Caller;
import eg.masroof.domain.auth.FailureWindowLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards {@code POST /api/ingest/sms} and {@code POST /api/ingest/parsed-transactions}
 * (REQ-SMS-001): at most {@value #MAX_BODY_BYTES} bytes of body (413 before anything is parsed,
 * whether or not Content-Length is sent) and {@value #LIMIT} requests per minute per member (429
 * with Retry-After). Runs after authentication.
 */
final class IngestGuardFilter extends OncePerRequestFilter {

  static final int MAX_BODY_BYTES = 16 * 1024;
  static final int LIMIT = 100;
  private static final java.util.Set<String> PATHS =
      java.util.Set.of("/api/ingest/parsed-transactions");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final java.util.Set<String> ALLOWED =
      java.util.Set.of(
          "deviceId",
          "localId",
          "source",
          "kind",
          "amountMinor",
          "currency",
          "occurredAt",
          "instrumentLast4",
          "senderId",
          "institution",
          "paymentMethod",
          "merchant");

  private final FailureWindowLimiter limiter =
      new FailureWindowLimiter(LIMIT, Duration.ofSeconds(60), 10_000);
  private final Clock clock;

  IngestGuardFilter(Clock clock) {
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Spring routes ";x=1" and "%73ms" to the controller, so compare the decoded path with matrix
    // parameters removed, never the raw request URI.
    return !"POST".equals(request.getMethod())
        || !PATHS.contains(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Object caller = request.getAttribute(AuthenticationFilter.CALLER);
    String key = caller instanceof Caller c ? c.memberId().toString() : "anonymous-dev";
    Instant now = clock.instant();
    Optional<Duration> wait = limiter.blockedFor(key, now);
    if (wait.isPresent()) {
      ProblemResponses.tooManyRequests(
          request, response, Math.max(1, (wait.get().toMillis() + 999) / 1000));
      return;
    }
    limiter.recordFailure(key, now); // counts requests, not failures
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      ProblemResponses.payloadTooLarge(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      ProblemResponses.payloadTooLarge(request, response);
      return;
    }
    if (hasUnknownProperty(body)) {
      ProblemResponses.unprocessable(
          request, response, "The request contains a property this endpoint does not accept");
      return;
    }
    chain.doFilter(new BufferedRequest(request, body), response);
  }

  /**
   * The contract says additionalProperties:false: a record that carries a {@code body}, {@code
   * text} or any other extra property is refused instead of silently ignored.
   */
  private static boolean hasUnknownProperty(byte[] body) {
    try {
      JsonNode root = JSON.readTree(body);
      if (root == null || !root.isObject()) {
        return false;
      }
      for (String name : root.propertyNames()) {
        if (!ALLOWED.contains(name)) {
          return true;
        }
      }
      return false;
    } catch (RuntimeException e) {
      return false; // not JSON: the controller answers 400
    }
  }

  /** The already-read body, replayable for the controller. */
  private static final class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("blocking reads only");
        }
      };
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
