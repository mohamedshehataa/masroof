package eg.masroof.adapters.out.security;

import eg.masroof.application.auth.AccessTokens;
import eg.masroof.application.auth.Account;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HS256 JWTs. The verifier accepts exactly one header ({@code alg=HS256}); {@code none}, other
 * algorithms and unknown structure are rejected, and the signature is compared in constant time.
 */
final class HmacJwtAccessTokens implements AccessTokens {

  static final Duration TTL = Duration.ofMinutes(15);
  private static final int MAX_CHARS = 2048;
  private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DEC = Base64.getUrlDecoder();
  private static final String HEADER =
      ENC.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));

  private final SecretKeySpec key;

  HmacJwtAccessTokens(byte[] secret) {
    this.key = new SecretKeySpec(secret, "HmacSHA256");
  }

  @Override
  public Duration ttl() {
    return TTL;
  }

  @Override
  public String issue(Account account, Instant now) {
    String payload =
        JSON.writeValueAsString(
            Map.of(
                "sub", account.userId().toString(),
                "hid", account.householdId().toString(),
                "role", account.role().name(),
                "iat", now.getEpochSecond(),
                "exp", now.plus(TTL).getEpochSecond(),
                "jti", UUID.randomUUID().toString()));
    String signingInput =
        HEADER + "." + ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return signingInput + "." + ENC.encodeToString(sign(signingInput));
  }

  @Override
  public Optional<Claims> verify(String token, Instant now) {
    if (token.length() > MAX_CHARS) {
      return Optional.empty();
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3) {
      return Optional.empty();
    }
    try {
      JsonNode header = JSON.readTree(DEC.decode(parts[0]));
      if (!header.isObject() || !"HS256".equals(header.path("alg").asString(""))) {
        return Optional.empty();
      }
      byte[] given = DEC.decode(parts[2]);
      if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), given)) {
        return Optional.empty();
      }
      JsonNode p = JSON.readTree(DEC.decode(parts[1]));
      if (!p.path("exp").isIntegralNumber()
          || !p.path("sub").isString()
          || !p.path("hid").isString()) {
        return Optional.empty();
      }
      Instant exp = Instant.ofEpochSecond(p.path("exp").asLong());
      if (!now.isBefore(exp)) {
        return Optional.empty();
      }
      return Optional.of(
          new Claims(
              UUID.fromString(p.path("sub").asString()),
              UUID.fromString(p.path("hid").asString()),
              p.path("role").asString(""),
              exp));
    } catch (RuntimeException malformed) {
      return Optional.empty();
    }
  }

  private byte[] sign(String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }
}
