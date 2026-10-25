package eg.masroof.domain.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed pseudonym for a client address in the audit log: HMAC-SHA-256 over the /24 (IPv4) or /48
 * (IPv6) prefix, so the value cannot be reversed or brute-forced over the small IPv4 space without
 * the server key, and does not single out one host inside a network. The same prefix and key always
 * give the same hash.
 */
public final class IpHasher {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Pattern V4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
  private static final Pattern V6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

  private final byte[] key;

  /** Creates a hasher keyed with a copy of {@code key}. */
  public IpHasher(byte[] key) {
    if (key.length == 0) {
      throw new IllegalArgumentException("audit key must not be empty");
    }
    this.key = key.clone();
  }

  /** Derives a labelled sub-key from a master secret (one labelled HMAC step). */
  public static IpHasher derivedFrom(byte[] master, String label) {
    return new IpHasher(
        hmac(master, ("masroof-audit-ip:" + label).getBytes(StandardCharsets.UTF_8)));
  }

  /** Lower-case hex HMAC of the address prefix. */
  public String hash(String ip) {
    return HexFormat.of().formatHex(hmac(key, prefix(ip).getBytes(StandardCharsets.UTF_8)));
  }

  static String prefix(String ip) {
    String s = ip.strip();
    if (V4.matcher(s).matches()) {
      return v4Prefix(s);
    }
    if (s.indexOf(':') >= 0 && V6_CHARS.matcher(s).matches()) {
      try {
        byte[] b = InetAddress.getByName(s).getAddress(); // a literal: never a DNS lookup
        if (b.length == 4) {
          return v4Prefix((b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".0");
        }
        return "v6:" + HexFormat.of().formatHex(b, 0, 6);
      } catch (UnknownHostException e) {
        return s;
      }
    }
    return s;
  }

  private static String v4Prefix(String s) {
    return s.substring(0, s.lastIndexOf('.')) + ".0/24";
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }
}
