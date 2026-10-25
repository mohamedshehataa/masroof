package eg.masroof.domain.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-EXP-001")
class BusinessCalendarTest {

  @Test
  void lateEveningUtcIsNextCairoDay() {
    // 22:30Z on 31 Dec is already 1 Jan in Cairo (UTC+2).
    assertThat(BusinessCalendar.dateOf(Instant.parse("2026-12-31T22:30:00Z")))
        .isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(BusinessCalendar.dateOf(Instant.parse("2026-12-31T21:59:59Z")))
        .isEqualTo(LocalDate.of(2026, 12, 31));
  }

  @Test
  void dayBoundsUseCairoMidnight() {
    LocalDate d = LocalDate.of(2026, 1, 15);
    assertThat(BusinessCalendar.startOfDay(d)).isEqualTo(Instant.parse("2026-01-14T22:00:00Z"));
    assertThat(BusinessCalendar.endOfDayExclusive(d))
        .isEqualTo(Instant.parse("2026-01-15T22:00:00Z"));
  }

  @Test
  void summerDaysUseDstOffset() {
    LocalDate d = LocalDate.of(2026, 7, 1);
    assertThat(BusinessCalendar.startOfDay(d)).isEqualTo(Instant.parse("2026-06-30T21:00:00Z"));
  }
}
