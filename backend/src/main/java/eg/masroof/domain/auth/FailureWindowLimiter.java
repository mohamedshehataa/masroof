package eg.masroof.domain.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Counts failures per key inside a fixed window that starts at the first failure; once {@code
 * maxFailures} are reached the key is blocked until the window ends, whatever is sent next
 * (REQ-SEC-007). In memory and bounded: when {@code maxKeys} live keys are tracked, new keys are
 * not tracked until some expire (the per-IP key still holds the attacker).
 */
public final class FailureWindowLimiter {

  private record Window(Instant start, int failures) {}

  private final int maxFailures;
  private final Duration window;
  private final int maxKeys;
  private final Map<String, Window> windows = new HashMap<>();

  /** Creates a limiter; all arguments must be positive. */
  public FailureWindowLimiter(int maxFailures, Duration window, int maxKeys) {
    if (maxFailures < 1 || maxKeys < 1 || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("limits must be positive");
    }
    this.maxFailures = maxFailures;
    this.window = window;
    this.maxKeys = maxKeys;
  }

  /** The time left in the block when {@code key} is blocked at {@code now}, else empty. */
  public synchronized Optional<Duration> blockedFor(String key, Instant now) {
    Window w = windows.get(key);
    if (w == null || w.failures() < maxFailures) {
      return Optional.empty();
    }
    Duration left = Duration.between(now, w.start().plus(window));
    return left.isNegative() || left.isZero() ? Optional.empty() : Optional.of(left);
  }

  /** Records one failure for {@code key} at {@code now}. */
  public synchronized void recordFailure(String key, Instant now) {
    Window w = windows.get(key);
    if (w != null && !expired(w, now)) {
      windows.put(key, new Window(w.start(), w.failures() + 1));
      return;
    }
    if (w == null && windows.size() >= maxKeys) {
      windows.values().removeIf(x -> expired(x, now));
      if (windows.size() >= maxKeys) {
        return;
      }
    }
    windows.put(key, new Window(now, 1));
  }

  /** Forgets the failures of {@code key} (a successful login). */
  public synchronized void reset(String key) {
    windows.remove(key);
  }

  private boolean expired(Window w, Instant now) {
    return !now.isBefore(w.start().plus(window));
  }
}
