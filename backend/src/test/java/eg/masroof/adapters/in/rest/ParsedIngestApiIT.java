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
    // the created expense id is returned for a new record (so the app can offer Undo)
    assertThat(a.get("expenseId").asString()).isEqualTo(String.valueOf(e.get("id")));
    // a retried upload (the first answer was lost) learns the id of what it created, so Undo stays
    // possible
    assertThat(b.get("expenseId").asString()).isEqualTo(a.get("expenseId").asString());
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
    // another household never gets the first household's expense id, not even by repeating the same
    // record
    assertThat(b.get("expenseId").asString()).isNotEqualTo(a.get("expenseId").asString());
    JsonNode again = JSON.readTree(send(tokenB, r).getContentAsString());
    assertThat(again.get("outcome").asString()).isEqualTo("DUPLICATE");
    assertThat(again.get("expenseId").asString()).isEqualTo(b.get("expenseId").asString());
    assertThat(again.get("expenseId").asString()).isNotEqualTo(a.get("expenseId").asString());
    assertThat(send(null, r).getStatus()).isEqualTo(401);
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 a refund waits for the user like other incoming money")
  void refundWaitsForReview() throws Exception {
    Map<String, Object> r = record("refund");
    r.put("kind", "REFUND");
    JsonNode a = JSON.readTree(send(tokenA, r).getContentAsString());
    assertThat(a.get("outcome").asString()).isEqualTo("ACCEPTED");
    assertThat(expenseOf(a.get("rawMessageId").asString()).get("status"))
        .isEqualTo("PENDING_REVIEW");
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 the exact JSON the phone sends for a received InstaPay transfer lands")
  void receivedInstapayTransferFromBothChannelsLands() throws Exception {
    // Byte-for-byte what the Android serializer produced (CapturedUploadTest) for the bank SMS
    // and the InstaPay app notification of the same 1.00 EGP transfer.
    String sms =
        "{\"deviceId\":\"DEV\",\"localId\":\"c72b77244507513cb47eb49644b2a58e186428c4f709b48821d908e9001a8237\","
            + "\"source\":\"SMS\",\"kind\":\"INCOME\",\"amountMinor\":100,\"currency\":\"EGP\","
            + "\"occurredAt\":\"2026-10-26T20:04:00Z\",\"senderId\":\"BDC\",\"instrumentLast4\":\"1094\","
            + "\"institution\":\"Banque du Caire\",\"paymentMethod\":\"INSTAPAY\","
            + "\"merchant\":\"MOHAMED MAHMOUD AHMED\"}";
    String push =
        "{\"deviceId\":\"DEV\",\"localId\":\"9ad3de7263d0374d0687e0704eb18b4969d4816b6d5d4ed98cf588380e411cd6\","
            + "\"source\":\"NOTIFICATION\",\"kind\":\"INCOME\",\"amountMinor\":100,\"currency\":\"EGP\","
            + "\"occurredAt\":\"2026-10-26T21:04:13Z\",\"senderId\":\"InstaPay\",\"institution\":\"InstaPay\","
            + "\"paymentMethod\":\"INSTAPAY\",\"merchant\":\"mohamed.shehataaaaa@instapay\"}";
    for (String wire : List.of(sms, push)) {
      MockHttpServletResponse r =
          mvc.perform(
                  post("/api/ingest/parsed-transactions")
                      .header("Authorization", "Bearer " + tokenA)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(wire.replace("DEV", device.toString())))
              .andReturn()
              .getResponse();
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(202);
      Map<String, Object> e =
          jdbc.queryForMap(
              "select * from expense where id = ?::uuid",
              JSON.readTree(r.getContentAsString()).get("expenseId").asString());
      assertThat(e.get("type")).isEqualTo("INCOME");
      assertThat(e.get("status")).isEqualTo("PENDING_REVIEW");
    }
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
            "رمز التحقق",
            "AMAZON OTPs",
            "AMAZON PINs",
            "امازون الباسورد",
            "كلمة مرور",
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
    // additionalProperties:false: an unknown property is refused (422) and never stored.
    assertThat(send(tokenA, bad).getStatus()).isEqualTo(422);
    for (String extra : List.of("text", "rawText", "message")) {
      bad = record("extra-" + extra);
      bad.put(extra, "Your OTP is 889900");
      assertThat(send(tokenA, bad).getStatus()).as(extra).isEqualTo(422);
    }
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

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 the raw-text ingest endpoints stay off (501) until STORY-122")
  void rawTextEndpointsAreOff() throws Exception {
    MockHttpServletResponse sms =
        mvc.perform(
                post("/api/ingest/sms")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(
                            Map.of(
                                "sender", "CIB",
                                "body", "Verification value 889900",
                                "receivedAt", Instant.now().toString(),
                                "deviceId", device.toString(),
                                "smsId", "x1"))))
            .andReturn()
            .getResponse();
    assertThat(sms.getStatus()).isEqualTo(501);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message where body like '%889900%'", Integer.class))
        .isZero();
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName(
      "REQ-SMS-003 the label spec: curly quotes, symbols and punctuation never lose a record")
  void labelNormalization() throws Exception {
    Map<String, String> cases =
        Map.of(
            "McDonald’s Maadi", "McDonald's Maadi",
            "Spinneys®", "Spinneys",
            "CAFE #A", "CAFE A",
            "Zooba | Zamalek", "Zooba Zamalek",
            "Yalla!Go", "Yalla Go");
    int n = 0;
    for (Map.Entry<String, String> c : cases.entrySet()) {
      Map<String, Object> r = record("label-spec-" + n++);
      r.put("merchant", c.getKey());
      JsonNode a = JSON.readTree(send(tokenA, r).getContentAsString());
      assertThat(a.get("outcome").asString()).as(c.getKey()).isEqualTo("ACCEPTED");
      assertThat(expenseOf(a.get("rawMessageId").asString()).get("merchant_raw"))
          .isEqualTo(c.getValue());
    }
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 income, incoming transfers and large amounts wait for the user")
  void pendingReview() throws Exception {
    Map<String, Object> income = record("pr-income");
    income.put("kind", "INCOME");
    JsonNode a = JSON.readTree(send(tokenA, income).getContentAsString());
    assertThat(expenseOf(a.get("rawMessageId").asString()).get("status"))
        .isEqualTo("PENDING_REVIEW");
    Map<String, Object> big = record("pr-big");
    big.put("amountMinor", 5_000_001L);
    JsonNode b = JSON.readTree(send(tokenA, big).getContentAsString());
    assertThat(expenseOf(b.get("rawMessageId").asString()).get("status"))
        .isEqualTo("PENDING_REVIEW");
    Map<String, Object> normal = record("pr-normal");
    JsonNode c = JSON.readTree(send(tokenA, normal).getContentAsString());
    assertThat(expenseOf(c.get("rawMessageId").asString()).get("status")).isEqualTo("CONFIRMED");
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 a non-UTF-8 body is 415 and a repeated JSON key is 422")
  void charsetAndDuplicateKeys() throws Exception {
    String withBody =
        JSON.writeValueAsString(record("cs-1")).replace("}", ",\"body\":\"Your OTP is 889900\"}");
    MockHttpServletResponse latin1 =
        mvc.perform(
                post("/api/ingest/parsed-transactions")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType("application/json;charset=ISO-8859-1")
                    .content(withBody.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)))
            .andReturn()
            .getResponse();
    assertThat(latin1.getStatus()).isEqualTo(415);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message r, expense e where r::text like '%889900%'"
                    + " or e::text like '%889900%'",
                Integer.class))
        .isZero();
    String duplicate =
        JSON.writeValueAsString(record("dup-1")).replace("}", ",\"merchant\":\"SECOND\"}");
    MockHttpServletResponse dup =
        mvc.perform(
                post("/api/ingest/parsed-transactions")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(duplicate))
            .andReturn()
            .getResponse();
    assertThat(dup.getStatus()).isEqualTo(422);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from expense where merchant_raw = 'SECOND'", Integer.class))
        .isZero();
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName(
      "REQ-SMS-003 a sender id with digits is refused and a sender with digits is masked to last four")
  void senderIds() throws Exception {
    Map<String, Object> r = record("sender-digits");
    r.put("senderId", "CIB1234");
    assertThat(send(tokenA, r).getStatus()).isEqualTo(400);
    assertThat(ExpenseMapper.maskSender("CIB")).isEqualTo("CIB");
    assertThat(ExpenseMapper.maskSender("+201000000012")).isEqualTo("••0012");
    assertThat(ExpenseMapper.maskSender(null)).isNull();
  }

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName(
      "REQ-SMS-003 429 after 100 requests a minute, and path variants cannot skip the guard")
  void rateLimitAndPathVariants() throws Exception {
    String email = "c-" + UUID.randomUUID() + "@example.com";
    user(householdA, UUID.randomUUID(), email);
    String token = login(email);
    for (int i = 0; i < 100; i++) {
      assertThat(send(token, record("rl-" + i)).getStatus()).isEqualTo(202);
    }
    MockHttpServletResponse limited = send(token, record("rl-over"));
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isNotNull();
    for (String path :
        List.of("/api/ingest/parsed-transactions;x=1", "/api/ingest/%70arsed-transactions")) {
      MockHttpServletResponse r =
          mvc.perform(
                  post(path)
                      .header("Authorization", "Bearer " + token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(JSON.writeValueAsString(record("rl-variant"))))
              .andReturn()
              .getResponse();
      // routed variants are limited; an unroutable one (404) cannot reach the controller at all
      assertThat(r.getStatus()).as(path).isIn(429, 404);
      assertThat(r.getStatus() == 404 && path.contains(";")).isFalse();
    }
    Map<String, Object> big = record("rl-big");
    big.put("merchant", "a".repeat(20_000));
    MockHttpServletResponse tooBig =
        mvc.perform(
                post("/api/ingest/parsed-transactions;x=1")
                    .header("Authorization", "Bearer " + tokenB)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(big)))
            .andReturn()
            .getResponse();
    assertThat(tooBig.getStatus()).isEqualTo(413);
  }
}
