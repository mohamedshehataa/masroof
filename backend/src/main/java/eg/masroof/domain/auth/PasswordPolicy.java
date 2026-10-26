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
 * beyond it (so the limit is enforced here, with a 400, instead of a 500); no NUL; at least five distinct characters and no single repeated or sequential run; and not a
 * common password. "Common" is the 10,000 most used passwords (SecLists, MIT), compared case-folded, also
 * after dropping trailing digits and symbols so {@code Password1234!} fails too.
 */
public final class PasswordPolicy {

  /** Shortest accepted password, in characters. */
  public static final int MIN_CHARS = 12;

  /** Longest accepted password, in UTF-8 bytes (BCrypt's input limit). */
  public static final int MAX_BYTES = 72;

  private static final int MIN_DISTINCT = 5;

  private static final String RESOURCE = "security/common-passwords.txt";

  /** Why a password was refused. */
  public enum Violation {
    TOO_SHORT,
    TOO_LONG,
    INVALID_CHARACTER,
    REPETITIVE,
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
    if (repetitive(password)) {
      return Optional.of(Violation.REPETITIVE);
    }
    // Digits, punctuation, symbols and any space (including NBSP) around the word do not make it
    // uncommon: "!Password1234", "1234password" and "password 1234" all reduce to "password".
    String folded = password.toLowerCase(Locale.ROOT);
    String stem = folded.replaceAll("^[\\p{N}\\p{P}\\p{S}\\p{Z}\\s]+|[\\p{N}\\p{P}\\p{S}\\p{Z}\\s]+$", "");
    if (Holder.COMMON.contains(folded.strip())
        || Holder.COMMON.contains(folded)
        || (!stem.isEmpty() && Holder.COMMON.contains(stem))) {
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
      case REPETITIVE -> "password is too repetitive";
      case COMMON -> "password is too common";
    };
  }

  /**
   * Fewer than five distinct characters, one block repeated ("123412341234"), or a single
   * ascending or descending run ("abcdefghijkl", "123456789012", digits wrap 9 to 0).
   */
  private static boolean repetitive(String password) {
    int[] cps = password.codePoints().toArray();
    if (password.codePoints().distinct().count() < MIN_DISTINCT) {
      return true;
    }
    for (int block = 1; block <= cps.length / 2; block++) {
      if (cps.length % block == 0 && repeatsBlock(cps, block)) {
        return true;
      }
    }
    return singleRun(cps);
  }

  private static boolean repeatsBlock(int[] cps, int block) {
    for (int i = block; i < cps.length; i++) {
      if (cps[i] != cps[i - block]) {
        return false;
      }
    }
    return true;
  }

  private static boolean singleRun(int[] cps) {
    int step = 0;
    for (int i = 1; i < cps.length; i++) {
      int delta = cps[i] - cps[i - 1];
      if (Character.isDigit(cps[i]) && Character.isDigit(cps[i - 1])) {
        int mod = ((delta % 10) + 10) % 10;
        delta = mod == 1 ? 1 : mod == 9 ? -1 : delta;
      }
      if (delta != 1 && delta != -1) {
        return false;
      }
      if (step == 0) {
        step = delta;
      } else if (delta != step) {
        return false;
      }
    }
    return true;
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
