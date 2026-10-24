package eg.masroof.archfixture.domain;

/** G4 probe fixture (REQ-OPS-024): a domain method named like money that returns {@code Double}. */
public final class BadReturn {
  Double totalFee() {
    return 0.0;
  }
}
