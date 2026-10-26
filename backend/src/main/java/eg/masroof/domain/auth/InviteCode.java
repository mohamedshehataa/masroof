package eg.masroof.domain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Invite codes: 256 random bits, URL-safe, shown once; only the SHA-256 is stored. Pure Java so the
 * rules are testable without Spring.
 */
public final class InviteCode {

  /** Longest code accepted before hashing; anything longer cannot be one of ours. */
  public static final int MAX_LENGTH = 128;

  private static final int BYTES = 32;

  private InviteCode() {}

  /** A fresh code with 256 bits of entropy (43 URL-safe characters). */
  public static String generate(SecureRandom random) {
    byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Lower-case hex SHA-256 of a code, the form stored in the database. */
  public static String hash(String code) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Compares two hashes in constant time. */
  public static boolean constantTimeEquals(String hashA, String hashB) {
    return MessageDigest.isEqual(
        hashA.getBytes(StandardCharsets.UTF_8), hashB.getBytes(StandardCharsets.UTF_8));
  }

  /** True when {@code presented} hashes to {@code storedHash}, compared in constant time. */
  public static boolean matches(String presented, String storedHash) {
    return constantTimeEquals(hash(presented), storedHash);
  }
}
