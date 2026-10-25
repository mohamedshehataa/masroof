package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * On-device parsed transactions end to end on real Postgres: strict validation, idempotency,
 * household scoping, and the guarantee that no message text is stored anywhere.
 */
@SpringBootTest(
    properties = {
      "masroof.jwt.secret=" + ParsedIngestApiIT.TEST_KEY,
      "masroof.trusted-proxies=127.0.0.0/8,::1/128",
      "masroof.jobs.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParsedIngestApiIT {

  static final String TEST_KEY = "it-only-signing-key-0123456789-abcdefghij";
  private static final String PASSWORD = "correct horse battery";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicInteger IPS = new AtomicInteger();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  UUID householdA;
  UUID householdB;
  UUID accountA;
  UUID ownerOfAccount;
  String tokenA;
  String tokenB;
  final UUID device = UUID.randomUUID();

  @BeforeAll
  void seed() throws Exception {
    householdA = UUID.randomUUID();
    householdB = UUID.randomUUID();
    jdbc.update("insert into household (id, name) values (?, 'A')", householdA);
    jdbc.update("insert into household (id, name) values (?, 'B')", householdB);
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    user(householdA, UUID.randomUUID(), emailA);
    user(householdB, UUID.randomUUID(), emailB);
    accountA = UUID.randomUUID();
    ownerOfAccount = UUID.randomUUID();
    jdbc.update(
        "insert into member (id, household_id, display_name) values (?, ?, 'Owner')",
        ownerOfAccount,
        householdA);
    jdbc.update(
        "insert into payment_account (id, household_id, member_id, type, institution, last4)"
            + " values (?, ?, ?, 'CARD', 'CIB', '1234')",
        accountA,
        householdA,
        ownerOfAccount);
    tokenA = login(emailA);
    tokenB = login(emailB);
  }

  private void user(UUID household, UUID member, String email) {
    jdbc.update(
        "insert into member (id, household_id, display_name, role) values (?, ?, 'Tester', 'ADMIN')",
        member,
        household);
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, ?, ?)",
        UUID.randomUUID(),
        member,
        email,
        new BCryptPasswordEncoder(4).encode(PASSWORD));
  }

  private String login(String email) throws Exception {
    MockHttpServletResponse r =
        mvc.perform(
                post("/auth/login")
                    .header("X-Forwarded-For", "198.51.100." + IPS.incrementAndGet())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    return JSON.readTree(r.getContentAsString()).get("accessToken").asString();
  }

  static String localId(String seed) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> record(String seed) {
    Map<String, Object> m = new HashMap<>();
    m.put("deviceId", device.toString());
    m.put("localId", localId(seed));
    m.put("source", "SMS");
    m.put("kind", "PURCHASE");
    m.put("amountMinor", 35000);
    m.put("currency", "EGP");
    m.put("occurredAt", Instant.now().minusSeconds(60).toString());
    m.put("instrumentLast4", "1234");
    m.put("senderId", "CIB");
    m.put("institution", "CIB");
    m.put("paymentMethod", "CARD");
    m.put("merchant", "CARREFOUR MAADI");
    return m;
  }

  private MockHttpServletResponse send(@Nullable String token, Map<String, Object> body)
      throws Exception {
    var b =
        post("/api/ingest/parsed-transactions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return mvc.perform(b).andReturn().getResponse();
  }

  private Map<String, Object> expenseOf(String rawId) {
    return jdbc.queryForMap(
        "select e.* from expense e join raw_message r on r.expense_id = e.id where r.id = ?::uuid",
        rawId);
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 a parsed record becomes a confirmed SMS expense once, with no body")
  void createsOnceWithNoText() throws Exception {
    Map<String, Object> r = record("one");
    JsonNode a = JSON.readTree(send(tokenA, r).getContentAsString());
    assertThat(a.get("outcome").asString()).isEqualTo("ACCEPTED");
    MockHttpServletResponse second = send(tokenA, r);
    assertThat(second.getStatus()).isEqualTo(202);
    JsonNode b = JSON.readTree(second.getContentAsString());
    assertThat(b.get("outcome").asString()).isEqualTo("DUPLICATE");
    assertThat(b.get("rawMessageId").asString()).isEqualTo(a.get("rawMessageId").asString());
    Map<String, Object> e = expenseOf(a.get("rawMessageId").asString());
    assertThat(e.get("source")).isEqualTo("SMS");
    assertThat(e.get("status")).isEqualTo("CONFIRMED");
    assertThat(e.get("merchant_raw")).isEqualTo("CARREFOUR MAADI");
    assertThat(e.get("payment_account_id")).isEqualTo(accountA);
    assertThat(e.get("paid_by_member_id")).isEqualTo(ownerOfAccount);
    assertThat(
            jdbc.queryForObject(
                "select body from raw_message where id = ?::uuid",
                String.class,
                a.get("rawMessageId").asString()))
        .isEmpty();
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 the same device and local id in another household is separate")
  void householdScoped() throws Exception {
    Map<String, Object> r = record("scoped");
    JsonNode a = JSON.readTree(send(tokenA, r).getContentAsString());
    JsonNode b = JSON.readTree(send(tokenB, r).getContentAsString());
    assertThat(b.get("outcome").asString()).isEqualTo("ACCEPTED");
    assertThat(b.get("rawMessageId").asString()).isNotEqualTo(a.get("rawMessageId").asString());
    assertThat(send(null, r).getStatus()).isEqualTo(401);
  }

  @Test
  @Tag("REQ-SMS-030")
  @DisplayName("REQ-SMS-030 secrets, digits and free text are refused by strict validation")
  void strictValidation() throws Exception {
    List<String> labels =
        List.of(
            "OTP value",
            "verification value",
            "كود البطاقة",
            "Code ****4821",
            "SHOP 4821",
            "ahmed 01000000000",
            "<script>",
            "رمز التحقق",
            "x".repeat(41));
    int n = 0;
    for (String label : labels) {
      Map<String, Object> r = record("label" + n++);
      r.put("merchant", label);
      assertThat(send(tokenA, r).getStatus()).as(label).isGreaterThanOrEqualTo(400).isLessThan(500);
    }
    Map<String, Object> bad = record("last4");
    bad.put("instrumentLast4", "12345");
    assertThat(send(tokenA, bad).getStatus()).isGreaterThanOrEqualTo(400);
    bad = record("kind");
    bad.put("kind", "OTP");
    assertThat(send(tokenA, bad).getStatus()).isGreaterThanOrEqualTo(400);
    bad = record("amount");
    bad.put("amountMinor", 0);
    assertThat(send(tokenA, bad).getStatus()).isGreaterThanOrEqualTo(400);
    bad = record("currency");
    bad.put("currency", "USD");
    assertThat(send(tokenA, bad).getStatus()).isEqualTo(422);
    bad = record("extra");
    bad.put("body", "Your OTP is 889900");
    // An unknown property is ignored, never stored: the secret is nowhere in the database
    // afterwards.
    assertThat(send(tokenA, bad).getStatus()).isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message r, expense e where r::text like '%889900%'"
                    + " or e::text like '%889900%'",
                Integer.class))
        .isZero();
    bad = record("sender");
    bad.put("senderId", "889900 is your code");
    assertThat(send(tokenA, bad).getStatus()).isGreaterThanOrEqualTo(400);
    bad = record("lid");
    bad.put("localId", "889900");
    assertThat(send(tokenA, bad).getStatus()).isGreaterThanOrEqualTo(400);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message where body <> '' and template_id = 'on-device'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from expense where merchant_raw ~ '[0-9]'", Integer.class))
        .isZero();
  }

  @Test
  @Tag("REQ-NTF-001")
  @DisplayName("REQ-NTF-001 an InstaPay notification record is stored as a PUSH transfer or income")
  void notificationKinds() throws Exception {
    Map<String, Object> r = record("ntf-out");
    r.put("source", "NOTIFICATION");
    r.put("kind", "TRANSFER_OUT");
    r.put("senderId", "InstaPay");
    r.put("institution", "InstaPay");
    r.put("paymentMethod", "INSTAPAY");
    r.put("instrumentLast4", null);
    r.put("merchant", "ahmed.ali@instapay");
    JsonNode a = JSON.readTree(send(tokenA, r).getContentAsString());
    Map<String, Object> e = expenseOf(a.get("rawMessageId").asString());
    assertThat(e.get("source")).isEqualTo("PUSH");
    assertThat(e.get("type")).isEqualTo("TRANSFER");
    assertThat(e.get("payment_method")).isEqualTo("INSTAPAY");
    Map<String, Object> in = record("ntf-in");
    in.put("source", "NOTIFICATION");
    in.put("kind", "INCOME");
    JsonNode b = JSON.readTree(send(tokenA, in).getContentAsString());
    assertThat(expenseOf(b.get("rawMessageId").asString()).get("type")).isEqualTo("INCOME");
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 an oversized request is answered 413 before it is parsed")
  void oversized() throws Exception {
    Map<String, Object> r = record("big");
    r.put("merchant", "a".repeat(20_000));
    assertThat(send(tokenA, r).getStatus()).isEqualTo(413);
  }
}
