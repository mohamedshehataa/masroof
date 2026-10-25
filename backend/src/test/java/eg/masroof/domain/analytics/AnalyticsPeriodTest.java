package eg.masroof.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("REQ-AN-001")
class AnalyticsPeriodTest {

  @Test
  @DisplayName("REQ-AN-001 current month with start day 1 is the calendar month")
  void calendarMonth() {
    AnalyticsPeriod p = AnalyticsPeriod.parse("current", LocalDate.of(2026, 10, 25), 1);
    assertThat(p.label()).isEqualTo("2026-10");
    assertThat(p.from()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(p.to()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(p.lengthDays()).isEqualTo(31);
  }

  @Test
  @DisplayName("REQ-AN-001 start day 25: 24 Oct still belongs to the month that began 25 Sep")
  void startDay25() {
    AnalyticsPeriod before = AnalyticsPeriod.parse("current", LocalDate.of(2026, 10, 24), 25);
    assertThat(before.label()).isEqualTo("2026-09");
    assertThat(before.from()).isEqualTo(LocalDate.of(2026, 9, 25));
    assertThat(before.to()).isEqualTo(LocalDate.of(2026, 10, 24));
    AnalyticsPeriod on = AnalyticsPeriod.parse("current", LocalDate.of(2026, 10, 25), 25);
    assertThat(on.label()).isEqualTo("2026-10");
    assertThat(on.to()).isEqualTo(LocalDate.of(2026, 11, 24));
    assertThat(AnalyticsPeriod.parse("previous", LocalDate.of(2026, 10, 25), 25).label())
        .isEqualTo("2026-09");
  }

  @Test
  @DisplayName("REQ-AN-001 January start day 25 crosses the year boundary")
  void yearBoundary() {
    AnalyticsPeriod p = AnalyticsPeriod.parse("current", LocalDate.of(2026, 1, 10), 25);
    assertThat(p.label()).isEqualTo("2025-12");
    assertThat(p.to()).isEqualTo(LocalDate.of(2026, 1, 24));
    assertThat(p.previous().label()).isEqualTo("2025-11");
  }

  @Test
  @DisplayName("REQ-AN-001 explicit YYYY-MM and ISO week specs")
  void explicitSpecs() {
    assertThat(AnalyticsPeriod.parse("2026-02", LocalDate.of(2026, 10, 1), 1).lengthDays())
        .isEqualTo(28);
    AnalyticsPeriod w = AnalyticsPeriod.parse("2026-W44", LocalDate.of(2026, 10, 1), 1);
    assertThat(w.week()).isTrue();
    assertThat(w.from()).isEqualTo(LocalDate.of(2026, 10, 26));
    assertThat(w.to()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(w.label()).isEqualTo("2026-W44");
    assertThat(w.previous().label()).isEqualTo("2026-W43");
    assertThat(AnalyticsPeriod.parse("2020-W53", LocalDate.of(2026, 10, 1), 1).from())
        .isEqualTo(LocalDate.of(2020, 12, 28));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "now", "2026-13", "2026-00", "2026-W00", "2026-W54", "2025-W53", "26-10"})
  @DisplayName("REQ-AN-001 malformed or non-existent periods are rejected")
  void rejects(String spec) {
    assertThatThrownBy(() -> AnalyticsPeriod.parse(spec, LocalDate.of(2026, 10, 1), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("REQ-AN-001 month start day outside 1..28 is rejected")
  void rejectsStartDay() {
    LocalDate today = LocalDate.of(2026, 10, 1);
    assertThatThrownBy(() -> AnalyticsPeriod.parse("current", today, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AnalyticsPeriod.parse("current", today, 29))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("REQ-AN-001 property: consecutive months tile the calendar for every start day")
  void monthsTile() {
    for (int startDay = 1; startDay <= 28; startDay++) {
      YearMonth ym = YearMonth.of(2023, 1);
      AnalyticsPeriod p = AnalyticsPeriod.month(ym, startDay);
      for (int i = 0; i < 48; i++) {
        AnalyticsPeriod next =
            AnalyticsPeriod.month(YearMonth.from(p.from()).plusMonths(1), startDay);
        assertThat(next.from()).isEqualTo(p.to().plusDays(1));
        assertThat(next.previous()).isEqualTo(p);
        assertThat(p.lengthDays()).isBetween(28, 31);
        p = next;
      }
    }
  }

  @Test
  @DisplayName("REQ-AN-001 property: every day belongs to exactly the current month containing it")
  void everyDayInItsMonth() {
    for (int startDay : new int[] {1, 5, 25, 28}) {
      for (LocalDate d = LocalDate.of(2025, 1, 1);
          d.isBefore(LocalDate.of(2027, 1, 1));
          d = d.plusDays(1)) {
        AnalyticsPeriod p = AnalyticsPeriod.currentMonth(d, startDay);
        assertThat(d).isBetween(p.from(), p.to());
      }
    }
  }

  @Test
  @DisplayName("REQ-AN-001 elapsed and remaining days")
  void elapsedAndLeft() {
    AnalyticsPeriod p = AnalyticsPeriod.parse("2026-10", LocalDate.of(2026, 10, 25), 1);
    assertThat(p.elapsedDays(LocalDate.of(2026, 9, 30))).isZero();
    assertThat(p.elapsedDays(LocalDate.of(2026, 10, 1))).isEqualTo(1);
    assertThat(p.elapsedDays(LocalDate.of(2026, 10, 25))).isEqualTo(25);
    assertThat(p.daysLeft(LocalDate.of(2026, 10, 25))).isEqualTo(6);
    assertThat(p.elapsedDays(LocalDate.of(2026, 11, 20))).isEqualTo(31);
    assertThat(p.daysLeft(LocalDate.of(2026, 11, 20))).isZero();
  }

  @Test
  @DisplayName("REQ-AN-001 instants are Cairo midnights, correct across the October DST change")
  void instantsFollowCairoDst() {
    // Egypt leaves DST on the last Thursday of October (29 Oct 2026): the month has 31 days + 1 h.
    AnalyticsPeriod p = AnalyticsPeriod.parse("2026-10", LocalDate.of(2026, 10, 1), 1);
    assertThat(p.start()).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
    assertThat(p.endExclusive()).isEqualTo(Instant.parse("2026-10-31T22:00:00Z"));
    assertThat(Duration.between(p.start(), p.endExclusive()))
        .isEqualTo(Duration.ofDays(31).plusHours(1));
  }

  @Test
  @DisplayName("REQ-AN-001 an inverted period is rejected")
  void invertedRejected() {
    LocalDate a = LocalDate.of(2026, 1, 2);
    LocalDate b = LocalDate.of(2026, 1, 1);
    assertThatThrownBy(() -> new AnalyticsPeriod("x", a, b, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
