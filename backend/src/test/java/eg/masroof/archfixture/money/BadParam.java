package eg.masroof.archfixture.money;

/**
 * G4 probe fixture (REQ-OPS-024): a method in a money package taking a {@code double} parameter.
 */
public final class BadParam {
  static long pay(double fee) {
    return (long) fee;
  }
}
