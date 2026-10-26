package eg.masroof.domain.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Password rules for every new password (invite acceptance, bootstrap admin): at least 12
 * characters; at most 72 UTF-8 bytes, because BCrypt reads no more and Spring's encoder throws
 * beyond it (so the limit is enforced here, with a 400, instead of a 500); no NUL; and not a common
 * password. "Common" is the 10,000 most used passwords (SecLists, MIT), compared case-folded, also
 * after dropping trailing digits and symbols so {@code Password1234!} fails too.
 */
public final class PasswordPolicy {

  /** Shortest accepted password, in characters. */
  public static final int MIN_CHARS = 12;

  /** Longest accepted password, in UTF-8 bytes (BCrypt's input limit). */
  public static final int MAX_BYTES = 72;

  private static final String RESOURCE = "security/common-passwords.txt";

  /** Why a password was refused. */
  public enum Violation {
    TOO_SHORT,
    TOO_LONG,
    INVALID_CHARACTER,
    COMMON
  }

  private static final class Holder {
    static final Set<String> COMMON = load();
  }

  private PasswordPolicy() {}

  /** The first rule the password breaks, or empty when it is acceptable. */
  public static Optional<Violation> check(String password) {
    if (password.indexOf('\0') >= 0) {
      return Optional.of(Violation.INVALID_CHARACTER);
    }
    if (password.isBlank() || password.strip().length() < MIN_CHARS) {
      return Optional.of(Violation.TOO_SHORT);
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      return Optional.of(Violation.TOO_LONG);
    }
    String folded = password.strip().toLowerCase(Locale.ROOT);
    String stem = folded.replaceAll("[\\p{N}\\p{P}\\p{S}]+$", "");
    if (Holder.COMMON.contains(folded) || (!stem.isEmpty() && Holder.COMMON.contains(stem))) {
      return Optional.of(Violation.COMMON);
    }
    return Optional.empty();
  }

  /** A fixed, value-free message for the violation (never echoes the password). */
  public static String message(Violation violation) {
    return switch (violation) {
      case TOO_SHORT -> "password must be at least " + MIN_CHARS + " characters";
      case TOO_LONG -> "password must be at most " + MAX_BYTES + " bytes";
      case INVALID_CHARACTER -> "password contains an invalid character";
      case COMMON -> "password is too common";
    };
  }

  private static Set<String> load() {
    try (InputStream in = PasswordPolicy.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + RESOURCE);
      }
      Set<String> words = new HashSet<>();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          String word = line.strip().toLowerCase(Locale.ROOT);
          if (!word.isEmpty() && word.charAt(0) != '#') {
            words.add(word);
          }
        }
      }
      return Set.copyOf(words);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
