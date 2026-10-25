package eg.masroof.domain.money;

import java.util.Objects;

/**
 * A monetary amount: {@code amountMinor} in the currency's minor unit (EGP 12.50 is 1250) and an
 * ISO-4217 alphabetic currency code (three upper-case ASCII letters). Immutable and never a
 * floating point value. Negative amounts are legal (refund deltas).
 *
 * @param amountMinor amount in minor units
 * @param currency ISO-4217 alphabetic code matching {@code ^[A-Z]{3}$}
 */
public record Money(long amountMinor, String currency) {

  private static final int CURRENCY_LENGTH = 3;

  /**
   * Validates the currency code.
   *
   * @throws NullPointerException if {@code currency} is null
   * @throws IllegalArgumentException if {@code currency} is not three characters A-Z
   */
  public Money {
    Objects.requireNonNull(currency, "currency");
    if (!isCurrencyCode(currency)) {
      throw new IllegalArgumentException("currency must be an ISO-4217 code (three letters A-Z)");
    }
  }

  /** Creates a Money; same validation as the canonical constructor. */
  public static Money of(long amountMinor, String currency) {
    return new Money(amountMinor, currency);
  }

  /** Zero in the given currency. */
  public static Money zero(String currency) {
    return new Money(0L, currency);
  }

  /** True when the amount is exactly zero. */
  public boolean isZero() {
    return amountMinor == 0L;
  }

  /** True when the amount is strictly greater than zero. */
  public boolean isPositive() {
    return amountMinor > 0L;
  }

  /** True when the amount is strictly less than zero. */
  public boolean isNegative() {
    return amountMinor < 0L;
  }

  /** True when both amounts are in the same currency. */
  public boolean hasSameCurrencyAs(Money other) {
    return currency.equals(other.currency);
  }

  private static boolean isCurrencyCode(String code) {
    if (code.length() != CURRENCY_LENGTH) {
      return false;
    }
    for (int i = 0; i < CURRENCY_LENGTH; i++) {
      char c = code.charAt(i);
      if (c < 'A' || c > 'Z') {
        return false;
      }
    }
    return true;
  }
}
