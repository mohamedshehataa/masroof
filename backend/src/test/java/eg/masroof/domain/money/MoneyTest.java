package eg.masroof.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** REQ-OPS-050: the domain Money that the contract's Money schema maps to. */
@Tag("REQ-OPS-050")
class MoneyTest {

  @Test
  @DisplayName("REQ-OPS-050 holds minor units and currency unchanged")
  void holdsValues() {
    Money m = Money.of(125_000L, "EGP");

    assertThat(m.amountMinor()).isEqualTo(125_000L);
    assertThat(m.currency()).isEqualTo("EGP");
    assertThat(m)
        .isEqualTo(new Money(125_000L, "EGP"))
        .hasSameHashCodeAs(new Money(125_000L, "EGP"));
  }

  @Test
  @DisplayName("REQ-OPS-050 differs by amount and by currency")
  void equalityIsByBoth() {
    assertThat(Money.of(1L, "EGP")).isNotEqualTo(Money.of(2L, "EGP"));
    assertThat(Money.of(1L, "EGP")).isNotEqualTo(Money.of(1L, "USD"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"EGP", "USD", "AAA", "ZZZ"})
  @DisplayName("REQ-OPS-050 accepts three upper-case ASCII letters, including the A and Z bounds")
  void acceptsValidCodes(String code) {
    assertThatCode(() -> Money.of(0L, code)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "", "E", "EG", "EGPP", "egp", "Egp", "EG1", "E P", "EGP\n", "@AA", "[AA", "AA@", "AA["
      })
  @DisplayName("REQ-OPS-050 rejects anything but three letters A-Z")
  void rejectsInvalidCodes(String code) {
    assertThatThrownBy(() -> Money.of(1L, code)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("REQ-OPS-050 rejects a null currency")
  @SuppressWarnings("NullAway")
  void rejectsNullCurrency() {
    assertThatThrownBy(() -> Money.of(1L, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("REQ-OPS-050 keeps the full long range, negatives included")
  void fullLongRange() {
    assertThat(Money.of(Long.MAX_VALUE, "EGP").amountMinor()).isEqualTo(Long.MAX_VALUE);
    assertThat(Money.of(Long.MIN_VALUE, "EGP").amountMinor()).isEqualTo(Long.MIN_VALUE);
  }

  @Test
  @DisplayName("REQ-OPS-050 zero, positive and negative predicates")
  void signPredicates() {
    Money zero = Money.zero("EGP");
    assertThat(zero.amountMinor()).isZero();
    assertThat(zero.currency()).isEqualTo("EGP");
    assertThat(zero.isZero()).isTrue();
    assertThat(zero.isPositive()).isFalse();
    assertThat(zero.isNegative()).isFalse();

    Money plus = Money.of(1L, "EGP");
    assertThat(plus.isZero()).isFalse();
    assertThat(plus.isPositive()).isTrue();
    assertThat(plus.isNegative()).isFalse();

    Money minus = Money.of(-1L, "EGP");
    assertThat(minus.isZero()).isFalse();
    assertThat(minus.isPositive()).isFalse();
    assertThat(minus.isNegative()).isTrue();
  }

  @Test
  @DisplayName("REQ-OPS-050 zero(currency) validates the currency")
  void zeroValidates() {
    assertThatThrownBy(() -> Money.zero("eg")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("REQ-OPS-050 same-currency check ignores the amount")
  void sameCurrency() {
    assertThat(Money.of(1L, "EGP").hasSameCurrencyAs(Money.of(-5L, "EGP"))).isTrue();
    assertThat(Money.of(1L, "EGP").hasSameCurrencyAs(Money.of(1L, "USD"))).isFalse();
  }
}
