package eg.masroof.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-AN-001")
class ProjectionsTest {

  @Test
  @DisplayName("REQ-AN-001 delta in basis points rounds half away from zero")
  void deltaBps() {
    assertThat(Projections.deltaBps(112_500, 100_000).getAsInt()).isEqualTo(1250);
    assertThat(Projections.deltaBps(50_000, 100_000).getAsInt()).isEqualTo(-5000);
    assertThat(Projections.deltaBps(100_000, 100_000).getAsInt()).isZero();
    // 1/3 = 33.33% -> 3333; 2/3 up = 6667
    assertThat(Projections.deltaBps(4, 3).getAsInt()).isEqualTo(3333);
    assertThat(Projections.deltaBps(5, 3).getAsInt()).isEqualTo(6667);
    assertThat(Projections.deltaBps(1, 3).getAsInt()).isEqualTo(-6667);
  }

  @Test
  @DisplayName("REQ-AN-001 delta is empty when the previous period is zero")
  void deltaEmptyWithoutBaseline() {
    assertThat(Projections.deltaBps(500, 0)).isEmpty();
    assertThat(Projections.deltaBps(0, 0)).isEmpty();
  }

  @Test
  @DisplayName("REQ-AN-002 share and savings rate")
  void shareAndSavings() {
    assertThat(Projections.shareBps(25, 100)).isEqualTo(2500);
    assertThat(Projections.shareBps(1, 0)).isZero();
    assertThat(Projections.savingsRateBps(100_000, 80_000).getAsInt()).isEqualTo(2000);
    assertThat(Projections.savingsRateBps(100_000, 120_000).getAsInt()).isEqualTo(-2000);
    assertThat(Projections.savingsRateBps(0, 5)).isEmpty();
  }

  @Test
  @DisplayName("REQ-AN-001 daily average and linear projection")
  void linear() {
    assertThat(Projections.dailyAverage(10_000, 4)).isEqualTo(2500);
    assertThat(Projections.dailyAverage(10_000, 0)).isZero();
    assertThat(Projections.linear(25_000, 25, 31)).isEqualTo(31_000);
    assertThat(Projections.linear(0, 0, 31)).isZero();
    assertThat(Projections.linear(25_000, 31, 31)).isEqualTo(25_000);
    assertThat(Projections.linear(25_000, 40, 31)).isEqualTo(25_000);
  }

  @Test
  @DisplayName("REQ-AN-001 rounding helper rejects a non-positive divisor")
  void divisorMustBePositive() {
    assertThatThrownBy(() -> Projections.divideRounded(1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("REQ-AN-001 weekday-aware: heavy Fridays push the projection above linear")
  void weekdayAware() {
    // 2026-10-01 is a Thursday. Spend 10_000 only on Fridays across the first 14 days.
    long[] daily = new long[14];
    LocalDate start = LocalDate.of(2026, 10, 1);
    for (int i = 0; i < 14; i++) {
      daily[i] = start.plusDays(i).getDayOfWeek() == java.time.DayOfWeek.FRIDAY ? 10_000 : 0;
    }
    // remaining 17 days (15 Oct..31 Oct) hold 2 Fridays (16, 23, 30 -> 3 Fridays)
    long projected = Projections.weekdayAware(start, daily, 31);
    assertThat(projected).isEqualTo(20_000 + 3 * 10_000);
    assertThat(projected).isNotEqualTo(Projections.linear(20_000, 14, 31));
  }

  @Test
  @DisplayName("REQ-AN-001 weekday-aware: an unseen weekday uses the overall mean")
  void weekdayAwareUnseenWeekday() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    // 2 elapsed days: Thu 1000, Fri 3000; the other weekdays fall back to mean 2000.
    long projected = Projections.weekdayAware(start, new long[] {1_000, 3_000}, 9);
    // Remaining Sat..Wed (5 days) and Thu, Fri (2 days): Thu 1000 + Fri 3000 + 5 * 2000
    assertThat(projected).isEqualTo(4_000 + 1_000 + 3_000 + 5 * 2_000);
  }

  @Test
  @DisplayName("REQ-AN-001 weekday-aware: empty and finished periods")
  void weekdayAwareEdges() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    assertThat(Projections.weekdayAware(start, new long[0], 31)).isZero();
    assertThat(Projections.weekdayAware(start, new long[] {5, 6, 7}, 3)).isEqualTo(18);
  }

  @Test
  @DisplayName("REQ-AN-001 property: projections never drop below spend and equal spend at the end")
  void projectionProperties() {
    Random rnd = new Random(42);
    for (int run = 0; run < 500; run++) {
      int length = 28 + rnd.nextInt(4);
      int elapsed = rnd.nextInt(length + 1);
      long[] daily = new long[elapsed];
      long total = 0;
      for (int i = 0; i < elapsed; i++) {
        daily[i] = rnd.nextInt(5) == 0 ? 0 : rnd.nextInt(2_000_000);
        total += daily[i];
      }
      LocalDate start = LocalDate.of(2026, 1, 1).plusDays(rnd.nextInt(700));
      long lin = Projections.linear(total, elapsed, length);
      long wk = Projections.weekdayAware(start, daily, length);
      if (elapsed == 0) {
        assertThat(lin).isZero();
        assertThat(wk).isZero();
      } else {
        assertThat(lin).isGreaterThanOrEqualTo(total);
        assertThat(wk).isGreaterThanOrEqualTo(total);
      }
      if (elapsed == length) {
        assertThat(lin).isEqualTo(total);
        assertThat(wk).isEqualTo(total);
      }
    }
  }

  @Test
  @DisplayName("REQ-AN-001 property: constant daily spend projects exactly, both ways")
  void constantSpendProjectsExactly() {
    Random rnd = new Random(7);
    for (int run = 0; run < 200; run++) {
      int length = 28 + rnd.nextInt(4);
      int elapsed = 7 + rnd.nextInt(length - 7);
      long perDay = 1L + rnd.nextInt(100_000);
      long[] daily = new long[elapsed];
      java.util.Arrays.fill(daily, perDay);
      LocalDate start = LocalDate.of(2026, 1, 1).plusDays(rnd.nextInt(700));
      assertThat(Projections.linear(perDay * elapsed, elapsed, length)).isEqualTo(perDay * length);
      assertThat(Projections.weekdayAware(start, daily, length)).isEqualTo(perDay * length);
    }
  }

  @Test
  @DisplayName("REQ-AN-002 property: delta and share stay consistent with exact fractions")
  void bpsProperties() {
    Random rnd = new Random(99);
    for (int run = 0; run < 500; run++) {
      long prev = 1L + rnd.nextInt(10_000_000);
      long cur = rnd.nextInt(20_000_000);
      long delta = Projections.deltaBps(cur, prev).getAsInt();
      // |exact - rounded| <= 0.5 bps
      long exactTimes2 = 2 * (cur - prev) * 10_000 / prev;
      assertThat(Math.abs(2 * delta - exactTimes2)).isLessThanOrEqualTo(2);
      long whole = prev + cur;
      assertThat(Projections.shareBps(cur, whole) + Projections.shareBps(prev, whole))
          .isBetween(9_999, 10_001);
    }
  }
}
