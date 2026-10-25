package eg.masroof.domain.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/** Decides whether a configured HS256 signing secret is acceptable. */
public final class JwtSecretPolicy {

  /** HS256 needs at least 256 bits of key. */
  public static final int MIN_BYTES = 32;

  private JwtSecretPolicy() {}

  /** Why {@code secret} is unacceptable, or empty when it is fine. Never echoes the secret. */
  public static Optional<String> problem(String secret) {
    if (secret.isBlank() || secret.contains("${")) {
      return Optional.of("is missing");
    }
    if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
      return Optional.of("is shorter than " + MIN_BYTES + " bytes");
    }
    if (secret.codePoints().distinct().count() < 8
        || secret.toLowerCase(Locale.ROOT).contains("changeme")) {
      return Optional.of("is a placeholder or has too little variety");
    }
    return Optional.empty();
  }
}
