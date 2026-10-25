package eg.masroof.domain.money;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A monetary amount: integer minor units (piasters for EGP) plus an ISO-4217 alphabetic currency.
 * Arithmetic is same-currency only and overflow-checked.
 */
public record Money(long amountMinor, String currency) {

  private static final Pattern ISO_4217 = Pattern.compile("^[A-Z]{3}$");

  /** Validates the currency code. */
  public Money {
    Objects.requireNonNull(currency, "currency");
    if (!ISO_4217.matcher(currency).matches()) {
      throw new IllegalArgumentException("currency must be an ISO-4217 code like EGP");
    }
  }

  /** Creates an amount in minor units. */
  public static Money of(long amountMinor, String currency) {
    return new Money(amountMinor, currency);
  }

  /** Zero in the given currency. */
  public static Money zero(String currency) {
    return new Money(0, currency);
  }

  /** Sum; same currency only, overflow-checked. */
  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
  }

  /** Difference; same currency only, overflow-checked. */
  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
  }

  /** Sign flip; overflow-checked. */
  public Money negate() {
    return new Money(Math.negateExact(amountMinor), currency);
  }

  public boolean isZero() {
    return amountMinor == 0;
  }

  public boolean isPositive() {
    return amountMinor > 0;
  }

  public boolean isNegative() {
    return amountMinor < 0;
  }

  /**
   * Splits into {@code parts} shares that sum exactly to this amount (largest remainder: the first
   * {@code amountMinor mod parts} shares carry one extra minor unit; the sign is preserved).
   */
  public Money[] allocate(int parts) {
    if (parts <= 0) {
      throw new IllegalArgumentException("parts must be positive");
    }
    long base = amountMinor / parts;
    long remainder = Math.abs(amountMinor % parts);
    long step = amountMinor < 0 ? -1 : 1;
    Money[] shares = new Money[parts];
    for (int i = 0; i < parts; i++) {
      shares[i] = new Money(i < remainder ? base + step : base, currency);
    }
    return shares;
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currency + " vs " + other.currency);
    }
  }
}
