package eg.masroof.application.auth;

/** Port: password hashing (BCrypt, cost at least 12). */
public interface PasswordHasher {

  /** Hashes a new password. */
  String hash(String password);

  /** Checks a password against a stored hash. */
  boolean matches(String password, String hash);

  /** A valid hash nobody knows the password of, for the unknown-user path. */
  String dummyHash();
}
