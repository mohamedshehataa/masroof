package eg.masroof.adapters.out.security;

import eg.masroof.application.auth.PasswordHasher;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.BCryptVersion;

/** BCrypt with cost 12. */
final class BcryptPasswordHasher implements PasswordHasher {

  static final int COST = 12;

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCryptVersion.$2B, COST);
  private final String dummy = encoder.encode(UUID.randomUUID().toString());

  @Override
  public String hash(String password) {
    return encoder.encode(password);
  }

  @Override
  public boolean matches(String password, String hash) {
    // BCrypt only reads the first 72 bytes; the contract caps passwords at 256 characters.
    return encoder.matches(password, hash);
  }

  @Override
  public String dummyHash() {
    return dummy;
  }
}
