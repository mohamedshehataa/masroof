package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Login, refresh rotation, logout, token validation and household scoping on real Postgres. */
@SpringBootTest(
    properties = {
      "masroof.jwt.secret=" + AuthApiIT.TEST_KEY,
      "masroof.cors.allowed-origins=http://localhost:5173,https://app.example.com",
      "masroof.trusted-proxies=127.0.0.0/8,::1/128"
    })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiIT {

  static final String TEST_KEY = "it-only-signing-key-0123456789-abcdefghij";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "correct horse battery";
  private static final String HS256 = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
  private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
  private static final AtomicInteger IPS = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired eg.masroof.domain.auth.IpHasher ipHasher;

  UUID householdA;
  UUID householdB;
  UUID userA;
  String emailA;
  String emailB;

  @BeforeAll
  void seed() {
    householdA = UUID.randomUUID();
    householdB = UUID.randomUUID();
    jdbc.update("insert into household (id, name) values (?, 'A')", householdA);
    jdbc.update("insert into household (id, name) values (?, 'B')", householdB);
    emailA = "a-" + UUID.randomUUID() + "@example.com";
    emailB = "b-" + UUID.randomUUID() + "@example.com";
    userA = user(householdA, emailA);
    user(householdB, emailB);
  }

  private UUID user(UUID household, String email) {
    UUID member = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    jdbc.update(
        "insert into member (id, household_id, display_name, role) values (?, ?, 'Tester', 'ADMIN')",
        member,
        household);
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, ?, ?)",
        user,
        member,
        email,
        new BCryptPasswordEncoder(4).encode(PASSWORD));
    return user;
  }

  private static String freshIp() {
    return "198.51.100." + IPS.incrementAndGet();
  }

  private MockHttpServletResponse login(String email, String password, String ip) throws Exception {
    return mvc.perform(
            post("/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", password))))
        .andReturn()
        .getResponse();
  }

  private JsonNode ok(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return JSON.readTree(r.getContentAsString());
  }

  private MockHttpServletResponse refresh(String token) throws Exception {
    return mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("refreshToken", token))))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse logout(String access, Map<String, Object> body) throws Exception {
    return mvc.perform(
            post("/auth/logout")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private static String forge(String header, Map<String, Object> claims) throws Exception {
    String input =
        ENC.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "."
            + ENC.encodeToString(JSON.writeValueAsBytes(claims));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(TEST_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return input + "." + ENC.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
  }

  private static Map<String, Object> claims(UUID user, UUID household, long exp) {
    return Map.of(
        "sub",
        user.toString(),
        "hid",
        household.toString(),
        "role",
        "ADMIN",
        "iat",
        exp - 900,
        "exp",
        exp,
        "jti",
        UUID.randomUUID().toString());
  }

  private MockHttpServletResponse getWith(String path, @Nullable String bearer) throws Exception {
    MockHttpServletRequestBuilder b = get(path);
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    }
    return mvc.perform(b).andReturn().getResponse();
  }

  private static void assertUnauthorized(MockHttpServletResponse r) {
    assertThat(r.getStatus()).isEqualTo(401);
    assertThat(r.getContentType()).startsWith("application/problem+json");
    assertThat(r.getHeader("WWW-Authenticate")).startsWith("Bearer");
  }

  private static String sha(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @Tag("REQ-SEC-001")
  @DisplayName(
      "REQ-SEC-001 login returns a token pair; the refresh token is stored only as SHA-256")
  void loginOk() throws Exception {
    JsonNode pair = ok(login(emailA.toUpperCase(Locale.ROOT), PASSWORD, freshIp()));
    assertThat(pair.path("tokenType").asString()).isEqualTo("Bearer");
    assertThat(pair.path("expiresIn").asInt()).isEqualTo(900);
    assertThat(pair.path("member").path("householdId").asString()).isEqualTo(householdA.toString());
    assertThat(pair.path("member").path("role").asString()).isEqualTo("ADMIN");
    String refreshToken = pair.path("refreshToken").asString();
    assertThat(Base64.getUrlDecoder().decode(refreshToken)).hasSize(32);
    Integer stored =
        jdbc.queryForObject(
            "select count(*) from refresh_token where token_hash = ? and user_id = ?"
                + " and expires_at between created_at + interval '29 days 20 hours'"
                + " and created_at + interval '30 days 4 hours'",
            Integer.class,
            sha(refreshToken),
            userA);
    assertThat(stored).isEqualTo(1);
    assertThat(getWith("/api/categories", pair.path("accessToken").asString()).getStatus())
        .isEqualTo(200);
  }

  @Test
  @Tag("REQ-SEC-001")
  @DisplayName("REQ-SEC-001 wrong password and unknown e-mail give the same generic 401")
  void loginBad() throws Exception {
    MockHttpServletResponse wrong = login(emailA, "not the password", freshIp());
    MockHttpServletResponse unknown = login("nobody@example.com", PASSWORD, freshIp());
    for (MockHttpServletResponse r : new MockHttpServletResponse[] {wrong, unknown}) {
      assertThat(r.getStatus()).isEqualTo(401);
      assertThat(r.getContentType()).startsWith("application/problem+json");
      assertThat(JSON.readTree(r.getContentAsString()).path("title").asString())
          .isEqualTo("Invalid credentials");
      assertThat(r.getContentAsString()).doesNotContain(emailA).doesNotContain("nobody");
    }
    JsonNode a = JSON.readTree(wrong.getContentAsString());
    JsonNode b = JSON.readTree(unknown.getContentAsString());
    assertThat(a.path("detail")).isEqualTo(b.path("detail"));
  }

  @Test
  @Tag("REQ-SEC-001")
  @DisplayName(
      "REQ-SEC-001 a body without a password or with a 300 char e-mail is a validation error")
  void loginValidation() throws Exception {
    MockHttpServletResponse r =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"a@example.com\"}"))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentType()).startsWith("application/problem+json");
    MockHttpServletResponse longEmail = login("a".repeat(295) + "@x.io", PASSWORD, freshIp());
    assertThat(longEmail.getStatus()).isEqualTo(400);
    assertThat(longEmail.getContentAsString()).doesNotContain(PASSWORD);
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName(
      "REQ-SEC-007 the 11th attempt for one e-mail is 429 with Retry-After, even with the right password")
  void rateLimitPerEmail() throws Exception {
    String email = "victim-" + UUID.randomUUID() + "@example.com";
    for (int i = 0; i < 10; i++) {
      assertThat(login(email, "bad password " + i, freshIp()).getStatus()).isEqualTo(401);
    }
    MockHttpServletResponse blocked = login(email, "bad password 11", freshIp());
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getContentType()).startsWith("application/problem+json");
    assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, 900);
    String locked = "locked-" + UUID.randomUUID() + "@example.com";
    user(householdB, locked);
    for (int i = 0; i < 10; i++) {
      login(locked, "bad password " + i, freshIp());
    }
    assertThat(login(locked, PASSWORD, freshIp()).getStatus()).isEqualTo(429);
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 the 11th failed attempt from one IP is 429 across different e-mails")
  void rateLimitPerIp() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 10; i++) {
      assertThat(
              login("u" + i + "-" + UUID.randomUUID() + "@example.com", "bad password", ip)
                  .getStatus())
          .isEqualTo(401);
    }
    assertThat(login(emailA, PASSWORD, ip).getStatus()).isEqualTo(429);
    assertThat(login(emailA, PASSWORD, freshIp()).getStatus()).isEqualTo(200);
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 a spoofed X-Forwarded-For from a public peer does not change the key")
  void spoofedHeaderIgnored() throws Exception {
    for (int i = 0; i < 11; i++) {
      MockHttpServletResponse r =
          mvc.perform(
                  post("/auth/login")
                      .with(
                          req -> {
                            req.setRemoteAddr("203.0.113.77");
                            return req;
                          })
                      .header("X-Forwarded-For", freshIp())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          JSON.writeValueAsString(
                              Map.of(
                                  "email", "s" + i + "@example.com", "password", "bad password"))))
              .andReturn()
              .getResponse();
      assertThat(r.getStatus()).isEqualTo(i < 10 ? 401 : 429);
    }
  }

  @Test
  @Tag("REQ-SEC-005")
  @DisplayName("REQ-SEC-005 refresh rotates; replaying the old token revokes the family and audits")
  void rotationAndReuse() throws Exception {
    JsonNode first = ok(login(emailA, PASSWORD, freshIp()));
    String r1 = first.path("refreshToken").asString();
    JsonNode second = ok(refresh(r1));
    String r2 = second.path("refreshToken").asString();
    assertThat(r2).isNotEqualTo(r1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where token_hash = ? and rotated_at is not null"
                    + " and family_id = (select family_id from refresh_token where token_hash = ?)",
                Integer.class,
                sha(r1),
                sha(r2)))
        .isEqualTo(1);

    MockHttpServletResponse replay = refresh(r1);
    assertThat(replay.getStatus()).isEqualTo(401);
    assertThat(replay.getContentType()).startsWith("application/problem+json");
    assertThat(refresh(r2).getStatus()).isEqualTo(401); // the legitimate successor is dead too
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token where revoked_at is null and family_id ="
                    + " (select family_id from refresh_token where token_hash = ?)",
                Integer.class,
                sha(r1)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action = 'REFRESH_REUSE' and household_id = ?",
                Integer.class,
                householdA))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  @Tag("REQ-SEC-005")
  @DisplayName("REQ-SEC-005 expired, unknown and oversized refresh tokens are 401")
  void refreshExpiredUnknown() throws Exception {
    String r = ok(login(emailA, PASSWORD, freshIp())).path("refreshToken").asString();
    jdbc.update(
        "update refresh_token set expires_at = now() - interval '1 minute' where token_hash = ?",
        sha(r));
    MockHttpServletResponse expired = refresh(r);
    assertThat(expired.getStatus()).isEqualTo(401);
    assertThat(JSON.readTree(expired.getContentAsString()).path("title").asString())
        .isEqualTo("Refresh token expired");
    assertThat(refresh("not-a-token").getStatus()).isEqualTo(401);
    assertThat(refresh("x".repeat(500)).getStatus()).isEqualTo(401);
  }

  @Test
  @Tag("REQ-SEC-006")
  @DisplayName("REQ-SEC-006 logout revokes the refresh token; the access token lives until exp")
  void logoutRevokes() throws Exception {
    JsonNode pair = ok(login(emailA, PASSWORD, freshIp()));
    String access = pair.path("accessToken").asString();
    String refreshToken = pair.path("refreshToken").asString();
    assertThat(logout(access, Map.of("refreshToken", refreshToken)).getStatus()).isEqualTo(204);
    assertThat(refresh(refreshToken).getStatus()).isEqualTo(401);
    assertThat(getWith("/api/categories", access).getStatus()).isEqualTo(200);
  }

  @Test
  @Tag("REQ-SEC-006")
  @DisplayName("REQ-SEC-006 allSessions revokes every token; another member's token is no oracle")
  void logoutAll() throws Exception {
    JsonNode a1 = ok(login(emailA, PASSWORD, freshIp()));
    JsonNode a2 = ok(login(emailA, PASSWORD, freshIp()));
    JsonNode b = ok(login(emailB, PASSWORD, freshIp()));
    assertThat(
            logout(
                    b.path("accessToken").asString(),
                    Map.of("refreshToken", a1.path("refreshToken").asString()))
                .getStatus())
        .isEqualTo(204);
    assertThat(refresh(a1.path("refreshToken").asString()).getStatus()).isEqualTo(200);
    assertThat(logout(a2.path("accessToken").asString(), Map.of("allSessions", true)).getStatus())
        .isEqualTo(204);
    assertThat(refresh(a2.path("refreshToken").asString()).getStatus()).isEqualTo(401);
    assertThat(refresh(b.path("refreshToken").asString()).getStatus()).isEqualTo(200);
  }

  @Test
  @Tag("REQ-SEC-012")
  @DisplayName("REQ-SEC-012 no token is 401 with WWW-Authenticate; /health stays open")
  void unauthenticated() throws Exception {
    for (String path :
        new String[] {"/api/expenses", "/api/categories", "/api/accounts", "/api/nothing"}) {
      assertUnauthorized(getWith(path, null));
    }
    assertThat(getWith("/health", null).getStatus()).isEqualTo(200);
  }

  private MockHttpServletResponse logoutWith(@Nullable String bearer, String body)
      throws Exception {
    MockHttpServletRequestBuilder b =
        post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body);
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    }
    return mvc.perform(b).andReturn().getResponse();
  }

  @Test
  @Tag("REQ-SEC-006")
  @DisplayName("REQ-SEC-006 logout with a missing or expired bearer still revokes by refresh token")
  void logoutWithoutValidBearer() throws Exception {
    String body1 = ok(login(emailA, PASSWORD, freshIp())).path("refreshToken").asString();
    assertThat(logoutWith(null, JSON.writeValueAsString(Map.of("refreshToken", body1))).getStatus())
        .isEqualTo(204);
    assertThat(refresh(body1).getStatus()).isEqualTo(401);

    String r2 = ok(login(emailA, PASSWORD, freshIp())).path("refreshToken").asString();
    String expired = forge(HS256, claims(userA, householdA, Instant.now().getEpochSecond() - 3600));
    assertThat(logoutWith(expired, JSON.writeValueAsString(Map.of("refreshToken", r2))).getStatus())
        .isEqualTo(204);
    assertThat(refresh(r2).getStatus()).isEqualTo(401);

    String r3 = ok(login(emailA, PASSWORD, freshIp())).path("refreshToken").asString();
    String r4 = ok(login(emailA, PASSWORD, freshIp())).path("refreshToken").asString();
    assertThat(
            logoutWith(
                    "garbage",
                    JSON.writeValueAsString(Map.of("refreshToken", r3, "allSessions", true)))
                .getStatus())
        .isEqualTo(204);
    assertThat(refresh(r3).getStatus()).isEqualTo(401);
    assertThat(refresh(r4).getStatus()).isEqualTo(401);
  }

  @Test
  @Tag("REQ-SEC-006")
  @DisplayName(
      "REQ-SEC-006 logout with unknown, revoked, empty or garbage input is an idempotent 204")
  void logoutIdempotent() throws Exception {
    assertThat(logoutWith(null, "{\"refreshToken\":\"nope\"}").getStatus()).isEqualTo(204);
    assertThat(logoutWith(null, "{}").getStatus()).isEqualTo(204);
    assertThat(logoutWith(null, "{\"refreshToken\":\"" + "x".repeat(500) + "\"}").getStatus())
        .isEqualTo(204);
    String r = ok(login(emailA, PASSWORD, freshIp())).path("refreshToken").asString();
    String body = JSON.writeValueAsString(Map.of("refreshToken", r));
    assertThat(logoutWith(null, body).getStatus()).isEqualTo(204);
    assertThat(logoutWith(null, body).getStatus()).isEqualTo(204);
  }

  @Test
  @Tag("REQ-SEC-051")
  @DisplayName("REQ-SEC-051 the unauthenticated rate limit covers /auth/logout")
  void logoutIsRateLimited() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 61; i++) {
      MockHttpServletResponse r =
          mvc.perform(
                  post("/auth/logout")
                      .header("X-Forwarded-For", ip)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn()
              .getResponse();
      assertThat(r.getStatus()).isEqualTo(i < 60 ? 204 : 429);
    }
  }

  @Test
  @Tag("REQ-SEC-012")
  @DisplayName(
      "REQ-SEC-012 expired, tampered, alg=none, wrong-household and non-bearer tokens are 401")
  void badTokens() throws Exception {
    long future = Instant.now().plusSeconds(600).getEpochSecond();
    String good = forge(HS256, claims(userA, householdA, future));
    assertThat(getWith("/api/categories", good).getStatus()).isEqualTo(200);

    assertUnauthorized(
        getWith(
            "/api/categories",
            forge(HS256, claims(userA, householdA, Instant.now().getEpochSecond() - 5))));

    String[] p = good.split("\\.", -1);
    String body =
        new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)
            .replace(householdA.toString(), householdB.toString());
    String tampered =
        p[0] + "." + ENC.encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "." + p[2];
    assertUnauthorized(getWith("/api/categories", tampered));

    String none =
        ENC.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + p[1]
            + ".";
    assertUnauthorized(getWith("/api/categories", none));

    assertUnauthorized(getWith("/api/categories", forge(HS256, claims(userA, householdB, future))));
    assertUnauthorized(
        getWith("/api/categories", forge(HS256, claims(UUID.randomUUID(), householdA, future))));
    assertUnauthorized(
        mvc.perform(get("/api/categories").header("Authorization", "Basic abc"))
            .andReturn()
            .getResponse());
  }

  @Test
  @Tag("REQ-SEC-012")
  @DisplayName("REQ-SEC-012 a token of another household gets 404 for resources it does not own")
  void householdIsolation() throws Exception {
    String accessA = ok(login(emailA, PASSWORD, freshIp())).path("accessToken").asString();
    String accessB = ok(login(emailB, PASSWORD, freshIp())).path("accessToken").asString();
    MockHttpServletResponse created =
        mvc.perform(
                post("/api/expenses")
                    .header("Authorization", "Bearer " + accessA)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"type\":\"EXPENSE\",\"amount\":{\"amountMinor\":5000,\"currency\":\"EGP\"},"
                            + "\"occurredAt\":\"2026-10-22T11:05:00Z\"}"))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    String id = JSON.readTree(created.getContentAsString()).path("id").asString();
    assertThat(getWith("/api/expenses/" + id, accessA).getStatus()).isEqualTo(200);
    MockHttpServletResponse foreign = getWith("/api/expenses/" + id, accessB);
    assertThat(foreign.getStatus()).isEqualTo(404);
    assertThat(foreign.getContentType()).startsWith("application/problem+json");
    assertThat(getWith("/api/expenses", accessB).getContentAsString()).doesNotContain(id);
  }

  @Test
  @Tag("REQ-SEC-010")
  @DisplayName("REQ-SEC-010 CORS echoes only configured origins")
  void cors() throws Exception {
    MockHttpServletResponse ok =
        mvc.perform(
                options("/api/expenses")
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "authorization"))
            .andReturn()
            .getResponse();
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    assertThat(ok.getHeader("Vary")).contains("Origin");
    assertThat(ok.getHeader("Access-Control-Allow-Methods")).contains("PATCH").contains("DELETE");
    MockHttpServletResponse evil =
        mvc.perform(
                options("/api/expenses")
                    .header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "POST"))
            .andReturn()
            .getResponse();
    assertThat(evil.getHeader("Access-Control-Allow-Origin")).isNull();
  }

  private MockHttpServletResponse healthFrom(String ip) throws Exception {
    return mvc.perform(get("/health").header("X-Forwarded-For", ip)).andReturn().getResponse();
  }

  @Test
  @Tag("REQ-SEC-051")
  @DisplayName("REQ-SEC-051 the 61st unauthenticated request in a window is 429 with Retry-After")
  void publicRateLimit() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 60; i++) {
      assertThat(healthFrom(ip).getStatus()).as("request " + (i + 1)).isEqualTo(200);
    }
    MockHttpServletResponse limited = healthFrom(ip);
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getContentType()).startsWith("application/problem+json");
    assertThat(JSON.readTree(limited.getContentAsString()).path("title").asString())
        .isEqualTo("Too Many Requests");
    assertThat(Integer.parseInt(limited.getHeader("Retry-After"))).isBetween(1, 60);
    assertThat(healthFrom(freshIp()).getStatus()).isEqualTo(200); // another client is unaffected
    // the login route shares the same budget
    MockHttpServletResponse login = login(emailA, PASSWORD, ip);
    assertThat(login.getStatus()).isEqualTo(429);
  }

  @Test
  @Tag("REQ-SEC-051")
  @DisplayName("REQ-SEC-051 header-less loopback health probes are never counted")
  void loopbackProbesUncounted() throws Exception {
    for (int i = 0; i < 70; i++) {
      assertThat(getWith("/health", null).getStatus()).isEqualTo(200);
    }
  }

  @Test
  @Tag("REQ-SEC-051")
  @DisplayName(
      "REQ-SEC-051 spoofed X-Forwarded-For from an untrusted peer does not escape the limit")
  void spoofedForwardedForStillLimited() throws Exception {
    for (int i = 0; i < 61; i++) {
      MockHttpServletResponse r =
          mvc.perform(
                  get("/health")
                      .with(
                          req -> {
                            req.setRemoteAddr("203.0.113.88");
                            return req;
                          })
                      .header("X-Forwarded-For", freshIp()))
              .andReturn()
              .getResponse();
      assertThat(r.getStatus()).isEqualTo(i < 60 ? 200 : 429);
    }
  }

  @Test
  @Tag("REQ-SEC-007")
  @DisplayName("REQ-SEC-007 failed and blocked logins are audited with a hashed IP and no e-mail")
  void loginAudit() throws Exception {
    String ip = freshIp();
    String victim = "audit-" + UUID.randomUUID() + "@example.com";
    UUID victimUser = user(householdA, victim);
    for (int i = 0; i < 10; i++) {
      assertThat(login(victim, "bad password " + i, ip).getStatus()).isEqualTo(401);
    }
    assertThat(login(victim, "bad password x", ip).getStatus()).isEqualTo(429);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from audit_log a join member m on m.id = a.member_id
                join app_user u on u.member_id = m.id
                where u.id = ? and a.action = 'LOGIN_FAILED' and a.ip_hash = ?
                """,
                Integer.class,
                victimUser,
                ipHasher.hash(ip)))
        .isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from audit_log where action = 'LOGIN_BLOCKED' and ip_hash = ?
                and member_id = (select member_id from app_user where id = ?)
                """,
                Integer.class,
                ipHasher.hash(ip),
                victimUser))
        .isEqualTo(1);
    assertThat(
            login("ghost-" + UUID.randomUUID() + "@example.com", PASSWORD, freshIp()).getStatus())
        .isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action = 'LOGIN_FAILED' and member_id is null",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  @Tag("REQ-SEC-013")
  @DisplayName("REQ-SEC-013 errors are problem+json with a traceId and never echo the body")
  void problemShape() throws Exception {
    MockHttpServletResponse malformed =
        mvc.perform(
                post("/auth/login")
                    .header("X-Forwarded-For", freshIp())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\": \"leak-me@example.com\", \"password\": SECRETBODY"))
            .andReturn()
            .getResponse();
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat(malformed.getContentType()).startsWith("application/problem+json");
    assertThat(malformed.getContentAsString())
        .doesNotContain("SECRETBODY")
        .doesNotContain("leak-me");
    JsonNode p = JSON.readTree(malformed.getContentAsString());
    assertThat(p.path("traceId").asString()).isNotBlank();
    assertThat(p.path("status").asInt()).isEqualTo(400);
    MockHttpServletResponse unauthorized = getWith("/api/expenses", null);
    assertThat(unauthorized.getContentType()).startsWith("application/problem+json");
    MockHttpServletResponse invalid =
        mvc.perform(
                post("/auth/login")
                    .header("X-Forwarded-For", freshIp())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"a@example.com\",\"password\":\"x\"}"))
            .andReturn()
            .getResponse();
    assertThat(invalid.getContentAsString()).doesNotContain("rejectedValue");
    MockHttpServletResponse notFound = getWith("/api/no-such-thing", null);
    assertThat(notFound.getStatus()).isEqualTo(401);
  }

  @Test
  @Tag("REQ-SEC-011")
  @DisplayName("REQ-SEC-011 security headers and a stack-trace-free error body")
  void headers() throws Exception {
    String token = ok(login(emailA, PASSWORD, freshIp())).path("accessToken").asString();
    MockHttpServletResponse r = getWith("/api/categories", token);
    assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(r.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(r.getHeader("Content-Security-Policy")).startsWith("default-src 'none'");
    assertThat(getWith("/health", null).getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    MockHttpServletResponse bad = getWith("/api/expenses/not-a-uuid", token);
    assertThat(bad.getContentAsString()).doesNotContain("Exception").doesNotContain("\tat ");
  }
}
