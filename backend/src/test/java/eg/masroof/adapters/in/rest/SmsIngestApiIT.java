package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import eg.masroof.application.sms.RawMessageRepository;
import eg.masroof.application.sms.SmsProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * SMS ingestion end to end on real Postgres: endpoint, idempotency, OTP rejection, the SKIP LOCKED
 * worker, rules-only parsing, household scoping and the source indicator on expenses.
 */
@SpringBootTest(
    properties = {
      "masroof.jwt.secret=" + SmsIngestApiIT.TEST_KEY,
      "masroof.trusted-proxies=127.0.0.0/8,::1/128",
      "masroof.jobs.enabled=false"
    })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmsIngestApiIT {

  static final String TEST_KEY = "it-only-signing-key-0123456789-abcdefghij";
  private static final String PASSWORD = "correct horse battery";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicInteger IPS = new AtomicInteger();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final String PURCHASE =
      "Dear Customer, your card ending with 1234 has been used for a purchase of EGP 350.00"
          + " at CARREFOUR MAADI on 12/03/2026 14:05. Available balance EGP 12,345.67";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SmsProcessor processor;
  @Autowired RawMessageRepository rawMessages;

  UUID householdA;
  UUID householdB;
  UUID memberA;
  UUID accountA;
  String tokenA;
  String tokenB;
  String tokenC;
  final UUID device = UUID.randomUUID();

  @BeforeAll
  void seed() throws Exception {
    householdA = UUID.randomUUID();
    householdB = UUID.randomUUID();
    jdbc.update("insert into household (id, name) values (?, 'A')", householdA);
    jdbc.update("insert into household (id, name) values (?, 'B')", householdB);
    memberA = UUID.randomUUID();
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    user(householdA, memberA, emailA);
    user(householdB, UUID.randomUUID(), emailB);
    UUID householdC = UUID.randomUUID();
    jdbc.update("insert into household (id, name) values (?, 'C')", householdC);
    String emailC = "c-" + UUID.randomUUID() + "@example.com";
    user(householdC, UUID.randomUUID(), emailC);
    tokenC = login(emailC);
    accountA = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    jdbc.update(
        "insert into member (id, household_id, display_name) values (?, ?, 'Owner')",
        owner,
        householdA);
    jdbc.update(
        "insert into payment_account (id, household_id, member_id, type, institution, last4)"
            + " values (?, ?, ?, 'CARD', 'CIB', '1234')",
        accountA,
        householdA,
        owner);
    ownerOfAccount = owner;
    tokenA = login(emailA);
    tokenB = login(emailB);
  }

  UUID ownerOfAccount;

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

  private MockHttpServletResponse ingest(
      @Nullable String token, String sender, String body, String smsId) throws Exception {
    return ingest(token, device, sender, body, smsId);
  }

  private MockHttpServletResponse ingest(
      @Nullable String token, UUID deviceId, String sender, String body, String smsId)
      throws Exception {
    Map<String, Object> request =
        Map.of(
            "sender", sender,
            "body", body,
            "receivedAt", Instant.now().minusSeconds(60).toString(),
            "deviceId", deviceId.toString(),
            "smsId", smsId);
    var builder =
        post("/api/ingest/sms")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(request));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return mvc.perform(builder).andReturn().getResponse();
  }

  private JsonNode json(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString());
  }

  private String status(String rawId) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "select processing_status from raw_message where id = ?::uuid", String.class, rawId));
  }

  private int expensesOf(UUID household) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "select count(*) from expense where household_id = ? and source = 'SMS'",
            Integer.class,
            household));
  }

  @Test
  @Tag("REQ-SMS-001")
  @Tag("REQ-SMS-010")
  @Tag("REQ-PARSE-030")
  @DisplayName("REQ-SMS-001 202 first, then the worker turns the SMS into a CONFIRMED expense")
  void acceptThenParse() throws Exception {
    int expensesBefore = expensesOf(householdA);
    java.time.LocalDate yesterday =
        java.time.LocalDate.now(java.time.ZoneId.of("Africa/Cairo")).minusDays(1);
    String dated =
        PURCHASE.replace(
            "12/03/2026",
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(yesterday));
    MockHttpServletResponse r = ingest(tokenA, "CIB", dated, "sms-1");
    assertThat(r.getStatus()).isEqualTo(202);
    JsonNode accepted = json(r);
    assertThat(accepted.get("outcome").asString()).isEqualTo("ACCEPTED");
    String rawId = accepted.get("rawMessageId").asString();
    assertThat(status(rawId)).isEqualTo("QUEUED");
    assertThat(expensesOf(householdA)).isEqualTo(expensesBefore);

    while (processor.drain(10) > 0) {
      // drain whatever is due, including messages of other tests
    }

    assertThat(status(rawId)).isEqualTo("DONE");
    Map<String, Object> e =
        jdbc.queryForMap(
            "select e.* from expense e join raw_message r on r.expense_id = e.id where r.id = ?::uuid",
            rawId);
    assertThat(e.get("type")).isEqualTo("EXPENSE");
    assertThat(e.get("amount_minor")).isEqualTo(35000L);
    assertThat(String.valueOf(e.get("currency")).strip()).isEqualTo("EGP");
    assertThat(e.get("status")).isEqualTo("CONFIRMED");
    assertThat(e.get("source")).isEqualTo("SMS");
    assertThat(e.get("merchant_raw")).isEqualTo("CARREFOUR MAADI");
    assertThat(e.get("payment_method")).isEqualTo("CARD");
    assertThat(
            java.util.Objects.requireNonNull((java.sql.Timestamp) e.get("occurred_at"))
                .toInstant()
                .atZone(java.time.ZoneId.of("Africa/Cairo"))
                .toLocalDate())
        .isEqualTo(yesterday);
    // REQ-SMS-013: last four 1234 maps to the payment account and so to its owner
    assertThat(e.get("payment_account_id")).isEqualTo(accountA);
    assertThat(e.get("paid_by_member_id")).isEqualTo(ownerOfAccount);
    assertThat(e.get("note")).isEqualTo("SMS CIB *1234");
  }

  @Test
  @Tag("REQ-SMS-001")
  @DisplayName("REQ-SMS-001 idempotent on (deviceId, smsId): the second upload is a DUPLICATE")
  void idempotent() throws Exception {
    JsonNode first = json(ingest(tokenA, "CIB", PURCHASE, "sms-idem"));
    MockHttpServletResponse again = ingest(tokenA, "CIB", PURCHASE, "sms-idem");
    assertThat(again.getStatus()).isEqualTo(202);
    JsonNode second = json(again);
    assertThat(second.get("outcome").asString()).isEqualTo("DUPLICATE");
    assertThat(second.get("rawMessageId").asString())
        .isEqualTo(first.get("rawMessageId").asString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message where external_id = 'sms-idem'", Integer.class))
        .isEqualTo(1);
    // another device may reuse the sms id
    assertThat(
            json(ingest(tokenA, UUID.randomUUID(), "CIB", PURCHASE, "sms-idem"))
                .get("outcome")
                .asString())
        .isEqualTo("ACCEPTED");
  }

  @Test
  @Tag("REQ-SMS-031")
  @DisplayName("REQ-SMS-031 an OTP that reaches the server is rejected and never stored")
  void otpRejected() throws Exception {
    int before =
        Objects.requireNonNull(
            jdbc.queryForObject("select count(*) from raw_message", Integer.class));
    MockHttpServletResponse r =
        ingest(tokenA, "CIB", "Your OTP is 482915. Do not share it with anyone.", "sms-otp");
    assertThat(r.getStatus()).isEqualTo(202);
    assertThat(json(r).get("outcome").asString()).isEqualTo("REJECTED_OTP");
    assertThat(jdbc.queryForObject("select count(*) from raw_message", Integer.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message where body like '%482915%'", Integer.class))
        .isZero();
  }

  @Test
  @Tag("REQ-SMS-031")
  @DisplayName("REQ-SMS-031 an OTP already in the queue is scrubbed by the worker, no expense")
  void otpInQueueIsScrubbed() throws Exception {
    UUID id = UUID.randomUUID();
    rawMessages.insertSms(
        new RawMessageRepository.NewSms(
            id,
            householdA,
            null,
            device,
            "sms-otp-queued",
            "CIB",
            "Verification code 771903",
            Instant.now(),
            Instant.now()));
    while (processor.drain(10) > 0) {
      // drain
    }
    assertThat(status(id.toString())).isEqualTo("IGNORED");
    assertThat(jdbc.queryForObject("select body from raw_message where id = ?", String.class, id))
        .isEqualTo("[redacted]");
    assertThat(
            jdbc.queryForObject("select expense_id from raw_message where id = ?", UUID.class, id))
        .isNull();
  }

  @Test
  @Tag("REQ-SMS-010")
  @Tag("REQ-SMS-012")
  @DisplayName(
      "REQ-SMS-010 unknown, promo, foreign-currency and reversal messages never become expenses")
  void notExpenses() throws Exception {
    int expensesBefore = expensesOf(householdA);
    String unknown =
        json(ingest(tokenA, "CIB", "Welcome to our bank", "sms-unknown"))
            .get("rawMessageId")
            .asString();
    String promo =
        json(ingest(
                tokenA, "CIB", "Win a chance to get EGP 10,000 cashback! Terms apply", "sms-promo"))
            .get("rawMessageId")
            .asString();
    String usd =
        json(ingest(
                tokenA,
                "CIB",
                "Your card ending with 1234 was used for USD 20.00 at NETFLIX.COM on 12/03/2026 14:05.",
                "sms-usd"))
            .get("rawMessageId")
            .asString();
    String reversal =
        json(ingest(
                tokenA, "CIB", "Your transaction of EGP 100.00 at SHELL was reversed", "sms-rev"))
            .get("rawMessageId")
            .asString();
    String declined =
        json(ingest(
                tokenA,
                "CIB",
                "Your card ending 1234 transaction of EGP 200.00 was declined",
                "sms-dec"))
            .get("rawMessageId")
            .asString();
    while (processor.drain(10) > 0) {
      // drain
    }
    assertThat(status(unknown)).isEqualTo("NEEDS_REVIEW");
    assertThat(status(promo)).isEqualTo("IGNORED");
    assertThat(status(usd)).isEqualTo("NEEDS_REVIEW");
    assertThat(status(reversal)).isEqualTo("NEEDS_REVIEW");
    assertThat(status(declined)).isEqualTo("IGNORED");
    assertThat(
            jdbc.queryForObject(
                "select error_code from raw_message where id = ?::uuid", String.class, usd))
        .isEqualTo("unsupported_currency");
    assertThat(expensesOf(householdA)).isEqualTo(expensesBefore);
    // nothing is dropped: the text is still there for review
    assertThat(
            jdbc.queryForObject(
                "select body from raw_message where id = ?::uuid", String.class, unknown))
        .isEqualTo("Welcome to our bank");
  }

  @Test
  @Tag("REQ-PARSE-031")
  @DisplayName(
      "REQ-PARSE-031 four concurrent workers process each message exactly once (SKIP LOCKED)")
  void concurrentWorkers() throws Exception {
    UUID concurrentDevice = UUID.randomUUID();
    for (int i = 0; i < 40; i++) {
      String body = "Purchase of EGP " + (10 + i) + ".00 at SHOP" + i + " on 12/03/2026 14:05";
      assertThat(ingest(tokenA, concurrentDevice, "CIB", body, "sms-c-" + i).getStatus())
          .isEqualTo(202);
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (int t = 0; t < 4; t++) {
        runs.add(pool.submit(() -> processor.drain(100)));
      }
      int handled = 0;
      for (Future<Integer> f : runs) {
        handled += f.get();
      }
      assertThat(handled).isGreaterThanOrEqualTo(40);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message where device_id = ? and processing_status = 'DONE'",
                Integer.class,
                concurrentDevice))
        .isEqualTo(40);
    assertThat(
            jdbc.queryForObject(
                "select count(distinct expense_id) from raw_message where device_id = ?",
                Integer.class,
                concurrentDevice))
        .isEqualTo(40);
    assertThat(
            jdbc.queryForObject(
                "select max(attempts) from raw_message where device_id = ?",
                Integer.class,
                concurrentDevice))
        .isEqualTo(1);
  }

  @Test
  @Tag("REQ-PARSE-032")
  @DisplayName(
      "REQ-PARSE-032 a failing attempt backs off, and the last attempt dead-letters as FAILED")
  void retryAndDeadLetter() {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    rawMessages.insertSms(
        new RawMessageRepository.NewSms(
            id, householdA, null, device, "sms-retry", "CIB", "Welcome", now, now));
    rawMessages.retryLater(id, now.plus(Duration.ofHours(1)), false, "processing_error", now);
    assertThat(rawMessages.claimNext(now.plusSeconds(5), Duration.ofMinutes(2)).map(m -> m.id()))
        .isNotEqualTo(java.util.Optional.of(id));
    assertThat(status(id.toString())).isEqualTo("QUEUED");
    rawMessages.retryLater(id, now, true, "processing_error", now);
    assertThat(status(id.toString())).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "select error_code from raw_message where id = ?", String.class, id))
        .isEqualTo("processing_error");
  }

  @Test
  @Tag("REQ-SMS-001")
  @Tag("REQ-SEC-012")
  @DisplayName("REQ-SMS-001 needs a valid access token; bad bodies are 4xx and never echoed")
  void authAndValidation() throws Exception {
    assertThat(ingest(null, "CIB", PURCHASE, "sms-noauth").getStatus()).isEqualTo(401);
    assertThat(ingest("not-a-token", "CIB", PURCHASE, "sms-badauth").getStatus()).isEqualTo(401);
    MockHttpServletResponse empty = ingest(tokenA, "CIB", "   ", "sms-empty");
    assertThat(empty.getStatus()).isEqualTo(422);
    assertThat(empty.getContentAsString()).doesNotContain("   ");
    assertThat(ingest(tokenA, "S".repeat(41), PURCHASE, "sms-sender").getStatus())
        .isGreaterThanOrEqualTo(400);
    assertThat(ingest(tokenA, "CIB", "x".repeat(2001), "sms-long").getStatus())
        .isGreaterThanOrEqualTo(400);
    assertThat(ingest(tokenA, "CIB", PURCHASE, "id with space").getStatus()).isEqualTo(422);
    MockHttpServletResponse missing =
        mvc.perform(
                post("/api/ingest/sms")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sender\":\"CIB\"}"))
            .andReturn()
            .getResponse();
    assertThat(missing.getStatus()).isEqualTo(400);
    MockHttpServletResponse future =
        mvc.perform(
                post("/api/ingest/sms")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sender\":\"CIB\",\"body\":\"Purchase of EGP 1.00\",\"receivedAt\":\"2099-01-01T00:00:00Z\","
                            + "\"deviceId\":\""
                            + device
                            + "\",\"smsId\":\"sms-future\"}"))
            .andReturn()
            .getResponse();
    assertThat(future.getStatus()).isEqualTo(422);
  }

  @Test
  @Tag("REQ-SMS-001")
  @Tag("REQ-AND-036")
  @DisplayName(
      "household scoping: B never sees A's SMS expenses or sources; same smsId is not a duplicate")
  void householdScoping() throws Exception {
    String rawA = json(ingest(tokenA, "CIB", PURCHASE, "sms-scope")).get("rawMessageId").asString();
    String rawB = json(ingest(tokenB, "CIB", PURCHASE, "sms-scope")).get("rawMessageId").asString();
    assertThat(rawB).isNotEqualTo(rawA);
    while (processor.drain(10) > 0) {
      // drain
    }
    MockHttpServletResponse listA =
        mvc.perform(
                get("/api/expenses")
                    .param("source", "SMS")
                    .header("Authorization", "Bearer " + tokenA))
            .andReturn()
            .getResponse();
    assertThat(listA.getStatus()).isEqualTo(200);
    JsonNode itemsA = json(listA).get("items");
    assertThat(itemsA.size()).isGreaterThan(0);
    JsonNode first = itemsA.get(0);
    assertThat(first.get("sources").size()).isEqualTo(1);
    JsonNode source = first.get("sources").get(0).get("rawMessage");
    assertThat(source.get("source").asString()).isEqualTo("SMS");
    assertThat(source.get("processingStatus").asString()).isEqualTo("DONE");
    assertThat(source.has("textPreview") && !source.get("textPreview").isNull()).isFalse();
    assertThat(listA.getContentAsString()).doesNotContain("CARREFOUR MAADI on 12/03");

    JsonNode itemsB =
        json(mvc.perform(get("/api/expenses").header("Authorization", "Bearer " + tokenB))
                .andReturn()
                .getResponse())
            .get("items");
    for (JsonNode item : itemsB) {
      assertThat(item.get("householdId").asString()).isEqualTo(householdB.toString());
    }
    String idA = first.get("id").asString();
    MockHttpServletResponse crossRead =
        mvc.perform(get("/api/expenses/" + idA).header("Authorization", "Bearer " + tokenB))
            .andReturn()
            .getResponse();
    assertThat(crossRead.getStatus()).isEqualTo(404);
    assertThat(rawMessages.sourcesOf(householdB, List.of(UUID.fromString(idA)))).isEmpty();
  }

  @Test
  @Tag("REQ-SMS-030")
  @Tag("REQ-SMS-031")
  @DisplayName(
      "REQ-SMS-030 code-like messages with a standalone number are refused and never stored")
  void structuralOtpIsRefused() throws Exception {
    String[] bodies = {
      "Your 3D Secure code for EGP 64.00 at EMU WEBSHOP is 556677",
      "Approve your payment of EGP 120.00 at NOON with 4455",
      "رقم التحقق 884213 لعملية شراء بمبلغ 500.00 جنيه"
    };
    int i = 0;
    for (String body : bodies) {
      JsonNode r = json(ingest(tokenA, "CIB", body, "sms-struct-otp-" + i++));
      assertThat(r.get("outcome").asString()).as(body).isEqualTo("REJECTED_OTP");
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message where external_id like 'sms-struct-otp-%'",
                Integer.class))
        .isZero();
  }

  @Test
  @Tag("REQ-SMS-030")
  @DisplayName(
      "REQ-SMS-030 a purchase that carries a code is kept, but only the masked body is stored")
  void codeBearingPurchaseIsStoredMasked() throws Exception {
    String raw =
        json(ingest(
                tokenA,
                "CIB",
                "Auth code:551234 for purchase of EGP 500.00 at AMAZON",
                "sms-masked-1"))
            .get("rawMessageId")
            .asString();
    String arabic =
        json(ingest(tokenA, "NBE", "تم خصم 1500 جنيه لدى جرير", "sms-masked-2"))
            .get("rawMessageId")
            .asString();
    String spaced =
        json(ingest(
                tokenA,
                "CIB",
                "Purchase of EGP 64.00 at EMU WEBSHOP. Code 556 677",
                "sms-masked-3"))
            .get("rawMessageId")
            .asString();
    String stored =
        jdbc.queryForObject("select body from raw_message where id = ?::uuid", String.class, raw);
    assertThat(stored).doesNotContain("551234").contains("500.00");
    assertThat(
            jdbc.queryForObject(
                "select body from raw_message where id = ?::uuid", String.class, spaced))
        .doesNotContain("556")
        .doesNotContain("677");
    assertThat(
            jdbc.queryForObject(
                "select body from raw_message where id = ?::uuid", String.class, arabic))
        .contains("1500");
    // no message of any test ever stored a run of 5 or more digits
    assertThat(
            jdbc.queryForObject(
                "select count(*) from raw_message where body ~ '[0-9]{5,}' and external_id <> 'sms-otp-queued'",
                Integer.class))
        .isZero();
    while (processor.drain(10) > 0) {
      // drain
    }
    assertThat(status(raw)).isEqualTo("DONE");
    assertThat(status(arabic)).isEqualTo("DONE");
  }

  @Test
  @Tag("REQ-SMS-001")
  @DisplayName(
      "REQ-SMS-001 more than 100 uploads a minute from one member answer 429 with Retry-After")
  void rateLimited() throws Exception {
    for (int i = 0; i < 100; i++) {
      assertThat(ingest(tokenC, "CIB", PURCHASE, "sms-rate-" + i).getStatus()).isEqualTo(202);
    }
    MockHttpServletResponse limited = ingest(tokenC, "CIB", PURCHASE, "sms-rate-over");
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isNotNull();
    assertThat(limited.getContentType()).startsWith("application/problem+json");
    // path variants Spring routes to the same controller are guarded too
    for (String variant : new String[] {"/api/ingest/sms;x=1", "/api/ingest/%73ms"}) {
      MockHttpServletResponse viaVariant =
          mvc.perform(
                  post(java.net.URI.create(variant))
                      .header("Authorization", "Bearer " + tokenC)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn()
              .getResponse();
      assertThat(viaVariant.getStatus()).as(variant).isEqualTo(429);
    }
    // another member is not affected
    assertThat(ingest(tokenA, "CIB", PURCHASE, "sms-rate-other").getStatus()).isEqualTo(202);
  }

  @Test
  @Tag("REQ-SMS-001")
  @DisplayName("REQ-SMS-001 a body over 16 KiB is refused with 413 before it is parsed")
  void payloadTooLarge() throws Exception {
    String huge = "{\"sender\":\"CIB\",\"body\":\"" + "x".repeat(20_000) + "\"}";
    MockHttpServletResponse r =
        mvc.perform(
                post("/api/ingest/sms")
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(huge))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(413);
    assertThat(r.getContentType()).startsWith("application/problem+json");
    MockHttpServletResponse viaVariant =
        mvc.perform(
                post(java.net.URI.create("/api/ingest/sms;x=1"))
                    .header("Authorization", "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(huge))
            .andReturn()
            .getResponse();
    assertThat(viaVariant.getStatus()).isEqualTo(413);
  }

  @Test
  @Tag("REQ-SMS-001")
  @DisplayName("the member behind the token is stored on the raw message")
  void memberRecorded() throws Exception {
    String raw = json(ingest(tokenA, "CIB", PURCHASE, "sms-member")).get("rawMessageId").asString();
    assertThat(
            jdbc.queryForObject(
                "select member_id from raw_message where id = ?::uuid", UUID.class, raw))
        .isEqualTo(memberA);
  }
}
