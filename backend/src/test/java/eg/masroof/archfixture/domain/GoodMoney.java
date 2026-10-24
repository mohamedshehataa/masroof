package eg.masroof.archfixture.domain;

/** Positive control for the money rule (REQ-OPS-024): minor units in a {@code long}. */
public final class GoodMoney {
  long amountMinor;

  long totalMinor(long feeMinor) {
    return amountMinor + feeMinor;
  }
}
