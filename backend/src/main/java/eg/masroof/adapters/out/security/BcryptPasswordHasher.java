package eg.masroof.adapters.out.security;

import eg.masroof.application.auth.PasswordHasher;
import eg.masroof.domain.auth.PasswordPolicy;
import java.nio.charset.StandardCharsets;
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
    // BCrypt reads at most 72 bytes and Spring's encoder throws beyond that; no stored password is
    // longer (PasswordPolicy), so a longer attempt simply does not match instead of causing a 500.
    return password.getBytes(StandardCharsets.UTF_8).length <= PasswordPolicy.MAX_BYTES
        && encoder.matches(password, hash);
  }

  @Override
  public String dummyHash() {
    return dummy;
  }
}
