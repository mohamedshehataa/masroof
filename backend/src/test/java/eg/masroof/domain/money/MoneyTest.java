package eg.masroof.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("REQ-OPS-050")
class MoneyTest {

  @Test
  @DisplayName("holds minor units and currency")
  void holdsValues() {
    Money m = Money.of(1250, "EGP");
    assertThat(m.amountMinor()).isEqualTo(1250);
    assertThat(m.currency()).isEqualTo("EGP");
    assertThat(m).isEqualTo(new Money(1250, "EGP")).isNotEqualTo(new Money(1250, "USD"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "EG", "EGPP", "egp", "E1P", " EGP", "EGP ", "EG\n"})
  void rejectsBadCurrency(String code) {
    assertThatThrownBy(() -> Money.of(1, code)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullCurrency() {
    assertThatThrownBy(() -> new Money(1, System.getProperty("masroof.absent")))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void plusMinusNegate() {
    Money a = Money.of(1000, "EGP");
    Money b = Money.of(250, "EGP");
    assertThat(a.plus(b)).isEqualTo(Money.of(1250, "EGP"));
    assertThat(a.minus(b)).isEqualTo(Money.of(750, "EGP"));
    assertThat(b.minus(a)).isEqualTo(Money.of(-750, "EGP"));
    assertThat(a.negate()).isEqualTo(Money.of(-1000, "EGP"));
    assertThat(Money.zero("USD")).isEqualTo(Money.of(0, "USD"));
  }

  @Test
  void mixedCurrenciesThrow() {
    Money egp = Money.of(1, "EGP");
    Money usd = Money.of(1, "USD");
    assertThatThrownBy(() -> egp.plus(usd))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EGP")
        .hasMessageContaining("USD");
    assertThatThrownBy(() -> egp.minus(usd)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overflowThrows() {
    Money max = Money.of(Long.MAX_VALUE, "EGP");
    Money min = Money.of(Long.MIN_VALUE, "EGP");
    assertThatThrownBy(() -> max.plus(Money.of(1, "EGP"))).isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> min.minus(Money.of(1, "EGP"))).isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(min::negate).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void signPredicates() {
    assertThat(Money.of(0, "EGP").isZero()).isTrue();
    assertThat(Money.of(1, "EGP").isZero()).isFalse();
    assertThat(Money.of(-1, "EGP").isZero()).isFalse();
    assertThat(Money.of(1, "EGP").isPositive()).isTrue();
    assertThat(Money.of(0, "EGP").isPositive()).isFalse();
    assertThat(Money.of(-1, "EGP").isPositive()).isFalse();
    assertThat(Money.of(-1, "EGP").isNegative()).isTrue();
    assertThat(Money.of(0, "EGP").isNegative()).isFalse();
    assertThat(Money.of(1, "EGP").isNegative()).isFalse();
  }

  @Test
  void allocateKnownCases() {
    assertThat(Money.of(100, "EGP").allocate(3))
        .containsExactly(Money.of(34, "EGP"), Money.of(33, "EGP"), Money.of(33, "EGP"));
    assertThat(Money.of(-100, "EGP").allocate(3))
        .containsExactly(Money.of(-34, "EGP"), Money.of(-33, "EGP"), Money.of(-33, "EGP"));
    assertThat(Money.of(1, "EGP").allocate(3))
        .containsExactly(Money.of(1, "EGP"), Money.of(0, "EGP"), Money.of(0, "EGP"));
    assertThat(Money.of(99, "EGP").allocate(3)).containsOnly(Money.of(33, "EGP"));
    assertThat(Money.of(7, "USD").allocate(1)).containsExactly(Money.of(7, "USD"));
    assertThat(Money.of(0, "EGP").allocate(2)).containsOnly(Money.of(0, "EGP"));
  }

  @Test
  void allocateRejectsNonPositiveParts() {
    assertThatThrownBy(() -> Money.of(1, "EGP").allocate(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.of(1, "EGP").allocate(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("property: allocate shares sum exactly, differ by at most one, keep currency")
  void allocateSumsExactly() {
    Random random = new Random(42);
    for (int i = 0; i < 2000; i++) {
      long amount = random.nextLong() % 10_000_000_000L;
      int parts = 1 + random.nextInt(12);
      Money total = Money.of(amount, "EGP");
      Money[] shares = total.allocate(parts);
      assertThat(shares).hasSize(parts);
      long sum = Arrays.stream(shares).mapToLong(Money::amountMinor).sum();
      assertThat(sum).isEqualTo(amount);
      long min = Arrays.stream(shares).mapToLong(Money::amountMinor).min().orElseThrow();
      long max = Arrays.stream(shares).mapToLong(Money::amountMinor).max().orElseThrow();
      assertThat(max - min).isLessThanOrEqualTo(1);
      assertThat(shares).allMatch(s -> s.currency().equals("EGP"));
    }
  }

  @Test
  @DisplayName("property: plus then minus returns the original")
  void plusMinusRoundTrip() {
    Random random = new Random(7);
    for (int i = 0; i < 1000; i++) {
      Money a = Money.of(random.nextLong() % 1_000_000_000_000L, "EGP");
      Money b = Money.of(random.nextLong() % 1_000_000_000_000L, "EGP");
      assertThat(a.plus(b).minus(b)).isEqualTo(a);
      assertThat(a.plus(b)).isEqualTo(b.plus(a));
      assertThat(a.negate().negate()).isEqualTo(a);
    }
  }
}
