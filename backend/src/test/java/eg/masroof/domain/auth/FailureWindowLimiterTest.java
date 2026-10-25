package eg.masroof.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-SEC-007")
class FailureWindowLimiterTest {

  private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(15);

  private FailureWindowLimiter limiter(int max, int keys) {
    return new FailureWindowLimiter(max, WINDOW, keys);
  }

  @Test
  @DisplayName("REQ-SEC-007 blocks only after maxFailures and reports the rest of the window")
  void blocksAtThreshold() {
    FailureWindowLimiter l = limiter(3, 10);
    l.recordFailure("a", T0);
    l.recordFailure("a", T0.plusSeconds(10));
    assertThat(l.blockedFor("a", T0.plusSeconds(20))).isEmpty();
    l.recordFailure("a", T0.plusSeconds(20));
    assertThat(l.blockedFor("a", T0.plusSeconds(60))).contains(Duration.ofMinutes(14));
    assertThat(l.blockedFor("b", T0.plusSeconds(60))).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-007 the block ends exactly when the window ends")
  void windowEnds() {
    FailureWindowLimiter l = limiter(1, 10);
    l.recordFailure("a", T0);
    assertThat(l.blockedFor("a", T0.plus(WINDOW).minusSeconds(1))).contains(Duration.ofSeconds(1));
    assertThat(l.blockedFor("a", T0.plus(WINDOW))).isEmpty();
    assertThat(l.blockedFor("a", T0.plus(WINDOW).plusSeconds(1))).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-007 a failure after the window starts a fresh window")
  void freshWindowAfterExpiry() {
    FailureWindowLimiter l = limiter(2, 10);
    l.recordFailure("a", T0);
    l.recordFailure("a", T0.plus(WINDOW));
    assertThat(l.blockedFor("a", T0.plus(WINDOW))).isEmpty();
    l.recordFailure("a", T0.plus(WINDOW).plusSeconds(5));
    assertThat(l.blockedFor("a", T0.plus(WINDOW).plusSeconds(6))).isPresent();
  }

  @Test
  @DisplayName("REQ-SEC-007 failures inside a blocked window do not extend it")
  void windowIsFixed() {
    FailureWindowLimiter l = limiter(1, 10);
    l.recordFailure("a", T0);
    l.recordFailure("a", T0.plus(WINDOW).minusSeconds(1));
    assertThat(l.blockedFor("a", T0.plus(WINDOW))).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-007 reset forgets a key")
  void resetClears() {
    FailureWindowLimiter l = limiter(1, 10);
    l.recordFailure("a", T0);
    l.reset("a");
    assertThat(l.blockedFor("a", T0)).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-007 memory is bounded: new keys are ignored while the table is full")
  void boundedKeys() {
    FailureWindowLimiter l = limiter(1, 2);
    l.recordFailure("a", T0);
    l.recordFailure("b", T0);
    l.recordFailure("c", T0.plusSeconds(1));
    assertThat(l.blockedFor("c", T0.plusSeconds(2))).isEmpty();
    assertThat(l.blockedFor("a", T0.plusSeconds(2))).isPresent();
    // expired keys make room again
    l.recordFailure("c", T0.plus(WINDOW).plusSeconds(1));
    assertThat(l.blockedFor("c", T0.plus(WINDOW).plusSeconds(2))).isPresent();
    assertThat(l.blockedFor("a", T0.plus(WINDOW).plusSeconds(2))).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-007 limits must be positive")
  void rejectsBadLimits() {
    assertThatIllegalArgumentException().isThrownBy(() -> new FailureWindowLimiter(0, WINDOW, 1));
    assertThatIllegalArgumentException().isThrownBy(() -> new FailureWindowLimiter(1, WINDOW, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new FailureWindowLimiter(1, Duration.ZERO, 1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new FailureWindowLimiter(1, Duration.ofSeconds(-1), 1));
  }
}
