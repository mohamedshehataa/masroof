package eg.masroof.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-AN-008")
class BudgetProgressTest {

  private static final List<Integer> DEFAULT = List.of(50, 80, 100);

  @Test
  @DisplayName("REQ-AN-008 progress in basis points rounds half away from zero")
  void rounding() {
    // 1 / 3 = 33.333...% -> 3333 bps; 2 / 3 = 66.666...% -> 6667 bps.
    assertThat(BudgetProgress.of(1, 3, DEFAULT).progressBps()).isEqualTo(3333);
    assertThat(BudgetProgress.of(2, 3, DEFAULT).progressBps()).isEqualTo(6667);
    // 1 / 8 = 12.5% exactly; 1 / 16 = 6.25% exactly.
    assertThat(BudgetProgress.of(1, 8, DEFAULT).progressBps()).isEqualTo(1250);
    assertThat(BudgetProgress.of(1, 16, DEFAULT).progressBps()).isEqualTo(625);
    // 5 / 80000 = 0.00625% -> 0.625 bps -> rounds up to 1.
    assertThat(BudgetProgress.of(5, 80_000, DEFAULT).progressBps()).isEqualTo(1);
  }

  @Test
  @DisplayName("REQ-AN-008 thresholds 50/80/100 fire exactly at the boundary")
  void thresholds() {
    assertThat(BudgetProgress.of(4_999, 10_000, DEFAULT).alertsFired()).isEmpty();
    assertThat(BudgetProgress.of(5_000, 10_000, DEFAULT).alertsFired()).containsExactly(50);
    assertThat(BudgetProgress.of(7_999, 10_000, DEFAULT).thresholdReached()).isEqualTo(50);
    assertThat(BudgetProgress.of(8_000, 10_000, DEFAULT).alertsFired()).containsExactly(50, 80);
    assertThat(BudgetProgress.of(10_000, 10_000, DEFAULT).thresholdReached()).isEqualTo(100);
    assertThat(BudgetProgress.of(12_000, 10_000, DEFAULT).progressBps()).isEqualTo(12_000);
  }

  @Test
  @DisplayName("REQ-AN-008 custom thresholds above 100 percent")
  void customThresholds() {
    BudgetProgress p = BudgetProgress.of(15_000, 10_000, List.of(100, 150));
    assertThat(p.alertsFired()).containsExactly(100, 150);
  }

  @Test
  @DisplayName("REQ-AN-008 no spend, refunds beyond spend, zero or negative limits, huge overspend")
  void edges() {
    assertThat(BudgetProgress.of(0, 10_000, DEFAULT).progressBps()).isZero();
    assertThat(BudgetProgress.of(-500, 10_000, DEFAULT).progressBps()).isZero();
    assertThat(BudgetProgress.of(-500, 10_000, DEFAULT).alertsFired()).isEmpty();
    assertThat(BudgetProgress.of(100, 0, DEFAULT).progressBps()).isEqualTo(10_000);
    assertThat(BudgetProgress.of(100, -50, DEFAULT).thresholdReached()).isEqualTo(100);
    assertThat(BudgetProgress.of(0, 0, DEFAULT).progressBps()).isZero();
    assertThat(BudgetProgress.of(1_000_000_000_000L, 1, DEFAULT).progressBps())
        .isEqualTo(BudgetProgress.MAX_BPS);
  }
}
