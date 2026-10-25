package eg.masroof.adapters.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.application.auth.AccessTokens.Claims;
import eg.masroof.application.auth.Account;
import eg.masroof.domain.auth.Role;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-SEC-001")
@Tag("REQ-SEC-012")
class HmacJwtAccessTokensTest {

  private static final byte[] SECRET =
      "q3JrT9vXk2Lw8NzYp0Hc5Fd7Gm1Sb4UeAi6Oj+Rt/Wx=".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
  private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

  private final HmacJwtAccessTokens jwt = new HmacJwtAccessTokens(SECRET);
  private final Account account =
      new Account(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "EGP", "M", Role.ADMIN, "x");

  private static String b64(String s) {
    return ENC.encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("REQ-SEC-001 token carries sub, hid, role, iat, exp (15 min) and a jti")
  void roundTrip() {
    String token = jwt.issue(account, NOW);
    Claims c = jwt.verify(token, NOW.plusSeconds(1)).orElseThrow();
    assertThat(c.userId()).isEqualTo(account.userId());
    assertThat(c.householdId()).isEqualTo(account.householdId());
    assertThat(c.role()).isEqualTo("ADMIN");
    assertThat(c.expiresAt()).isEqualTo(NOW.plusSeconds(900));
    String payload =
        new String(
            Base64.getUrlDecoder().decode(token.split("\\.", -1)[1]), StandardCharsets.UTF_8);
    assertThat(payload).contains("\"jti\"").contains("\"iat\":" + NOW.getEpochSecond());
    assertThat(jwt.ttl().toMinutes()).isEqualTo(15);
    assertThat(jwt.issue(account, NOW)).isNotEqualTo(token); // unique jti
  }

  @Test
  @DisplayName("REQ-SEC-012 an expired token is refused, the last valid second is accepted")
  void expiry() {
    String token = jwt.issue(account, NOW);
    assertThat(jwt.verify(token, NOW.plusSeconds(899))).isPresent();
    assertThat(jwt.verify(token, NOW.plusSeconds(900))).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-012 a tampered payload or signature is refused")
  void tampered() {
    String[] p = jwt.issue(account, NOW).split("\\.", -1);
    String payload =
        new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)
            .replace(account.householdId().toString(), UUID.randomUUID().toString());
    assertThat(jwt.verify(p[0] + "." + b64(payload) + "." + p[2], NOW)).isEmpty();
    char flipped = p[2].charAt(0) == 'A' ? 'B' : 'A';
    assertThat(jwt.verify(p[0] + "." + p[1] + "." + flipped + p[2].substring(1), NOW)).isEmpty();
    assertThat(jwt.verify(p[0] + "." + p[1] + ".", NOW)).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-012 alg=none and other algorithms are refused")
  void algNone() {
    String payload =
        b64(
            "{\"sub\":\""
                + account.userId()
                + "\",\"hid\":\""
                + account.householdId()
                + "\",\"role\":\"ADMIN\",\"exp\":"
                + NOW.plusSeconds(600).getEpochSecond()
                + "}");
    assertThat(jwt.verify(b64("{\"alg\":\"none\"}") + "." + payload + ".", NOW)).isEmpty();
    assertThat(jwt.verify(b64("{\"alg\":\"none\"}") + "." + payload, NOW)).isEmpty();
    assertThat(jwt.verify(b64("{\"alg\":\"HS512\"}") + "." + payload + ".AAAA", NOW)).isEmpty();
    assertThat(jwt.verify(b64("{\"alg\":\"NONE\"}") + "." + payload + ".", NOW)).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-012 a token signed with another key is refused")
  void wrongKey() {
    HmacJwtAccessTokens other =
        new HmacJwtAccessTokens(
            "another-secret-another-secret-another!".getBytes(StandardCharsets.UTF_8));
    assertThat(jwt.verify(other.issue(account, NOW), NOW)).isEmpty();
  }

  private String signed(String payloadJson) throws Exception {
    String input = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + b64(payloadJson);
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(SECRET, "HmacSHA256"));
    return input + "." + ENC.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
  }

  private String payload(@Nullable String iat, String exp, @Nullable String jti) {
    return "{\"sub\":\""
        + account.userId()
        + "\",\"hid\":\""
        + account.householdId()
        + "\",\"role\":\"ADMIN\""
        + (iat == null ? "" : ",\"iat\":" + iat)
        + ",\"exp\":"
        + exp
        + (jti == null ? "" : ",\"jti\":\"" + jti + "\"")
        + "}";
  }

  @Test
  @DisplayName("REQ-SEC-012 iat and jti are required; lifetime and future iat are bounded")
  void claimChecks() throws Exception {
    long now = NOW.getEpochSecond();
    // baseline: a well-formed token passes
    assertThat(jwt.verify(signed(payload("" + now, "" + (now + 900), "j1")), NOW)).isPresent();
    // missing or blank iat/jti, non-numeric iat
    assertThat(jwt.verify(signed(payload(null, "" + (now + 900), "j1")), NOW)).isEmpty();
    assertThat(jwt.verify(signed(payload("" + now, "" + (now + 900), null)), NOW)).isEmpty();
    assertThat(jwt.verify(signed(payload("" + now, "" + (now + 900), "  ")), NOW)).isEmpty();
    assertThat(jwt.verify(signed(payload("\"x\"", "" + (now + 900), "j1")), NOW)).isEmpty();
    // lifetime: TTL + 60 s skew is the limit
    assertThat(jwt.verify(signed(payload("" + now, "" + (now + 960), "j1")), NOW)).isPresent();
    assertThat(jwt.verify(signed(payload("" + now, "" + (now + 961), "j1")), NOW)).isEmpty();
    // iat in the future: 60 s skew allowed, 61 s not
    assertThat(jwt.verify(signed(payload("" + (now + 60), "" + (now + 900), "j1")), NOW))
        .isPresent();
    assertThat(jwt.verify(signed(payload("" + (now + 61), "" + (now + 900), "j1")), NOW)).isEmpty();
    // a month-long token signed with the right key is refused
    assertThat(jwt.verify(signed(payload("" + now, "" + (now + 2_592_000), "j1")), NOW)).isEmpty();
  }

  @Test
  @DisplayName("REQ-SEC-012 garbage never throws")
  void garbage() {
    for (String s :
        new String[] {
          "", "a", "a.b", "a.b.c.d", "...", "!!!.???.***", "e30.e30.e30", "x".repeat(5000)
        }) {
      assertThat(jwt.verify(s, NOW)).as(s.length() + "").isEmpty();
    }
  }
}
