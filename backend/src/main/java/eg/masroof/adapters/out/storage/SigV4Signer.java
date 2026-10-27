package eg.masroof.adapters.out.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 for the S3 service (Authorization header form). */
final class SigV4Signer {

  private static final DateTimeFormatter AMZ_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private final String accessKey;
  private final String secretKey;
  private final String region;

  SigV4Signer(String accessKey, String secretKey, String region) {
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.region = region;
  }

  static String amzDate(Instant now) {
    return AMZ_DATE.format(now);
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /**
   * The {@code Authorization} header value. {@code headers} holds every header to sign, names in
   * any case (at least {@code host} and {@code x-amz-date}); {@code canonicalUri} is already
   * percent-encoded and {@code canonicalQuery} already sorted and encoded.
   */
  String authorization(
      String method,
      String canonicalUri,
      String canonicalQuery,
      Map<String, String> headers,
      String payloadHash,
      Instant now) {
    TreeMap<String, String> sorted = new TreeMap<>();
    headers.forEach((k, v) -> sorted.put(k.toLowerCase(java.util.Locale.ROOT), v.strip()));
    StringBuilder canonicalHeaders = new StringBuilder();
    StringBuilder signedHeaders = new StringBuilder();
    sorted.forEach(
        (k, v) -> {
          canonicalHeaders.append(k).append(':').append(v).append('\n');
          if (!signedHeaders.isEmpty()) {
            signedHeaders.append(';');
          }
          signedHeaders.append(k);
        });
    String canonicalRequest =
        method
            + '\n'
            + canonicalUri
            + '\n'
            + canonicalQuery
            + '\n'
            + canonicalHeaders
            + '\n'
            + signedHeaders
            + '\n'
            + payloadHash;
    String day = DAY.format(now);
    String scope = day + "/" + region + "/s3/aws4_request";
    String stringToSign =
        "AWS4-HMAC-SHA256\n"
            + amzDate(now)
            + '\n'
            + scope
            + '\n'
            + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
    key = hmac(key, region);
    key = hmac(key, "s3");
    key = hmac(key, "aws4_request");
    String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
    return "AWS4-HMAC-SHA256 Credential="
        + accessKey
        + '/'
        + scope
        + ", SignedHeaders="
        + signedHeaders
        + ", Signature="
        + signature;
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is always available", e);
    }
  }
}
