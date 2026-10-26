package eg.masroof.domain.budget;

import eg.masroof.domain.analytics.Projections;
import java.util.List;

/**
 * Progress of spending against a budget limit, in integer arithmetic only.
 *
 * @param progressBps spent / limit in basis points, rounded half away from zero, capped at {@link
 *     #MAX_BPS}
 * @param alertsFired the configured thresholds (percent) already reached, ascending
 */
public record BudgetProgress(long spent, long limit, int progressBps, List<Integer> alertsFired) {

  /** Upper cap, 100x the limit, so absurd overspend cannot overflow. */
  public static final int MAX_BPS = 1_000_000;

  private static final long BPS_PER_PERCENT = 100L;
  private static final long CAP_FACTOR = 100L;
  private static final int FULL_BPS = 10_000;

  /**
   * Computes progress. A non-positive limit (rollover ate the whole budget) counts as fully used:
   * 100% with nothing spent, and the cap once anything is, so every threshold up to 200% can fire.
   */
  public static BudgetProgress of(long spent, long limit, List<Integer> thresholds) {
    int bps;
    if (limit <= 0) {
      bps = spent > 0 ? MAX_BPS : FULL_BPS;
    } else if (spent <= 0) {
      bps = 0;
    } else if (spent > Math.multiplyExact(limit, CAP_FACTOR)) {
      bps = MAX_BPS;
    } else {
      bps = Math.min(MAX_BPS, Projections.shareBps(spent, limit));
    }
    List<Integer> fired =
        thresholds.stream().filter(t -> bps >= t * BPS_PER_PERCENT).sorted().toList();
    return new BudgetProgress(spent, limit, bps, fired);
  }

  /** The highest threshold reached, or 0. */
  public int thresholdReached() {
    return alertsFired.isEmpty() ? 0 : alertsFired.get(alertsFired.size() - 1);
  }
}
