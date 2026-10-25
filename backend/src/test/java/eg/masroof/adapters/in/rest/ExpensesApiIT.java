package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Manual expense entry end to end against real Postgres (STORY-020, STORY-021). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpensesApiIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  UUID household;
  UUID otherHousehold;
  UUID member1;
  UUID member2;
  UUID account;
  UUID groceries;
  UUID car;
  UUID fuel;
  UUID otherCategory;
  UUID archivedCategory;

  @BeforeAll
  void seed() {
    household = UUID.randomUUID();
    otherHousehold = UUID.randomUUID();
    jdbc.update(
        "insert into household (id, name, created_at) values (?, 'Test', now() - interval '1 day')",
        household);
    jdbc.update("insert into household (id, name) values (?, 'Other')", otherHousehold);
    member1 = UUID.randomUUID();
    member2 = UUID.randomUUID();
    for (UUID m : List.of(member1, member2)) {
      jdbc.update(
          "insert into member (id, household_id, display_name) values (?, ?, 'M')", m, household);
    }
    account = UUID.randomUUID();
    jdbc.update(
        "insert into payment_account (id, household_id, member_id, type, nickname)"
            + " values (?, ?, ?, 'CASH', 'Wallet')",
        account,
        household,
        member1);
    jdbc.queryForList("select seed_household_categories(?)", household);
    jdbc.queryForList("select seed_household_categories(?)", otherHousehold);
    groceries = categoryId(household, "groceries");
    car = categoryId(household, "car");
    fuel = categoryId(household, "car.fuel");
    otherCategory = categoryId(otherHousehold, "groceries");
    archivedCategory = categoryId(household, "pets");
    jdbc.update("update category set archived = true where id = ?", archivedCategory);
  }

  private UUID categoryId(UUID h, String key) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "select id from category where household_id = ? and reference_key = ?",
            UUID.class,
            h,
            key));
  }

  private record Reply(int status, Map<String, Object> body, MockHttpServletResponse raw) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(String key) {
      return (List<Map<String, Object>>) Objects.requireNonNull(body.get(key));
    }
  }

  private Reply send(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
    String text = response.getContentAsString();
    Map<String, Object> body =
        text.isEmpty()
            ? Map.of()
            : JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
    return new Reply(response.getStatus(), body, response);
  }

  private static Map<String, Object> money(long minor) {
    return Map.of("amountMinor", minor, "currency", "EGP");
  }

  private Map<String, Object> expense(long minor, String occurredAt) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("type", "EXPENSE");
    m.put("amount", money(minor));
    m.put("occurredAt", occurredAt);
    return m;
  }

  private Reply create(Map<String, Object> body) throws Exception {
    return send(
        post("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private Reply createWithKey(Map<String, Object> body, String key) throws Exception {
    return send(
        post("/api/expenses")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private Reply createRaw(String json) throws Exception {
    return send(post("/api/expenses").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> errors(Reply r) {
    return (List<Map<String, Object>>) Objects.requireNonNull(r.body().get("errors"));
  }

  @Test
  @Tag("REQ-EXP-002")
  @DisplayName(
      "REQ-EXP-002 create returns 201 with Location and a CONFIRMED expense that GET returns")
  void createAndGet() throws Exception {
    Map<String, Object> body = expense(20_000, "2026-10-22T11:05:00Z");
    body.put("categoryId", groceries.toString());
    body.put("paidByMemberId", member2.toString());
    body.put("paymentAccountId", account.toString());
    body.put("paymentMethod", "CASH");
    body.put("merchantRaw", "بنزين");
    body.put("notes", "weekly");
    body.put("tags", List.of("car"));

    Reply created = create(body);

    assertThat(created.status()).isEqualTo(201);
    String id = (String) created.body().get("id");
    assertThat(created.raw().getHeader("Location")).isEqualTo("/api/expenses/" + id);
    assertThat(created.body())
        .containsEntry("status", "CONFIRMED")
        .containsEntry("type", "EXPENSE")
        .containsEntry("paymentMethod", "CASH")
        .containsEntry("merchantRaw", "بنزين")
        .containsEntry("householdId", household.toString())
        .containsKey("amount")
        .containsEntry("occurredAt", "2026-10-22T11:05:00Z");
    assertThat(created.body().get("tags")).isEqualTo(List.of("car"));
    assertMoney(created.body().get("amount"), 20_000);

    Reply fetched = send(get("/api/expenses/" + id));
    assertThat(fetched.status()).isEqualTo(200);
    assertThat(fetched.body()).isEqualTo(created.body());
  }

  @Test
  @Tag("REQ-EXP-002")
  @DisplayName("REQ-EXP-002 TRANSFER and CASH_WITHDRAWAL are accepted as their own types")
  void otherTypes() throws Exception {
    for (String type : List.of("TRANSFER", "CASH_WITHDRAWAL", "INCOME", "REFUND")) {
      Map<String, Object> body = expense(500, "2026-10-22T11:05:00Z");
      body.put("type", type);
      Reply r = create(body);
      assertThat(r.status()).isEqualTo(201);
      assertThat(r.body()).containsEntry("type", type);
    }
  }

  @Test
  @Tag("REQ-EXP-021")
  @DisplayName("REQ-EXP-021 invalid money is 422 on amount fields; a JSON float is 400")
  void moneyValidation() throws Exception {
    for (long bad : new long[] {0, -5, 10_000_000_000_001L}) {
      Reply r = create(expense(bad, "2026-10-22T11:05:00Z"));
      assertThat(r.status()).as("amountMinor %s", bad).isEqualTo(422);
      assertThat(r.raw().getContentType()).startsWith("application/problem+json");
      assertThat(errors(r)).extracting(e -> e.get("field")).containsExactly("amount.amountMinor");
    }
    assertThat(create(expense(10_000_000_000_000L, "2026-10-22T11:05:00Z")).status())
        .isEqualTo(201);

    for (String code : List.of("EG", "egp", "EGPP")) {
      Reply r =
          createRaw(
              "{\"type\":\"EXPENSE\",\"amount\":{\"amountMinor\":100,\"currency\":\""
                  + code
                  + "\"},\"occurredAt\":\"2026-10-22T11:05:00Z\"}");
      assertThat(r.status()).as("currency %s", code).isEqualTo(422);
      assertThat(errors(r)).extracting(e -> e.get("field")).containsExactly("amount.currency");
    }

    Reply unknown =
        createRaw(
            "{\"type\":\"EXPENSE\",\"amount\":{\"amountMinor\":100,\"currency\":\"ZZZ\"},"
                + "\"occurredAt\":\"2026-10-22T11:05:00Z\"}");
    assertThat(unknown.status()).isEqualTo(422);

    Reply usd =
        createRaw(
            "{\"type\":\"EXPENSE\",\"amount\":{\"amountMinor\":100,\"currency\":\"USD\"},"
                + "\"occurredAt\":\"2026-10-22T11:05:00Z\"}");
    assertThat(usd.status()).isEqualTo(422);
    assertThat(usd.body()).containsEntry("title", "Foreign currency needs FX");

    Reply floaty =
        createRaw(
            "{\"type\":\"EXPENSE\",\"amount\":{\"amountMinor\":1e5,\"currency\":\"EGP\"},"
                + "\"occurredAt\":\"2026-10-22T11:05:00Z\"}");
    assertThat(floaty.status()).isEqualTo(400);
    assertThat(floaty.raw().getContentType()).startsWith("application/problem+json");
  }

  @Test
  @Tag("REQ-EXP-002")
  @DisplayName(
      "REQ-EXP-002 contract validation: missing fields and malformed JSON are 400 problems")
  void contractValidation() throws Exception {
    Reply missing = createRaw("{\"type\":\"EXPENSE\"}");
    assertThat(missing.status()).isEqualTo(400);
    assertThat(errors(missing)).extracting(e -> e.get("field")).contains("amount", "occurredAt");
    assertThat(createRaw("{not json").status()).isEqualTo(400);
    assertThat(
            createRaw(
                    "{\"type\":\"BOGUS\",\"amount\":{\"amountMinor\":1,\"currency\":\"EGP\"},"
                        + "\"occurredAt\":\"2026-10-22T11:05:00Z\"}")
                .status())
        .isEqualTo(400);
    assertThat(send(get("/api/expenses/not-a-uuid")).status()).isEqualTo(400);
  }

  @Test
  @Tag("REQ-EXP-002")
  @DisplayName("REQ-EXP-002 business rules: future date, long notes, too many tags, bad references")
  void businessRules() throws Exception {
    Map<String, Object> future = expense(100, Instant.now().plusSeconds(3 * 86_400).toString());
    assertThat(create(future).status()).isEqualTo(422);
    assertThat(errors(create(future)))
        .extracting(e -> e.get("field"))
        .containsExactly("occurredAt");

    Map<String, Object> notes = expense(100, "2026-10-22T11:05:00Z");
    notes.put("notes", "x".repeat(501));
    assertThat(errors(create(notes))).extracting(e -> e.get("field")).containsExactly("notes");
    notes.put("notes", "x".repeat(500));
    assertThat(create(notes).status()).isEqualTo(201);

    Map<String, Object> tags = expense(100, "2026-10-22T11:05:00Z");
    tags.put("tags", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"));
    assertThat(errors(create(tags))).extracting(e -> e.get("field")).containsExactly("tags");
    tags.put("tags", List.of("x".repeat(31)));
    assertThat(create(tags).status()).isEqualTo(422);

    Map<String, Object> foreign = expense(100, "2026-10-22T11:05:00Z");
    foreign.put("categoryId", otherCategory.toString());
    assertThat(create(foreign).status()).isEqualTo(404);
    Map<String, Object> missingMember = expense(100, "2026-10-22T11:05:00Z");
    missingMember.put("paidByMemberId", UUID.randomUUID().toString());
    assertThat(create(missingMember).status()).isEqualTo(404);
    Map<String, Object> missingAccount = expense(100, "2026-10-22T11:05:00Z");
    missingAccount.put("paymentAccountId", UUID.randomUUID().toString());
    assertThat(create(missingAccount).status()).isEqualTo(404);
    Map<String, Object> archived = expense(100, "2026-10-22T11:05:00Z");
    archived.put("categoryId", archivedCategory.toString());
    assertThat(create(archived).status()).isEqualTo(422);

    Map<String, Object> items = expense(100, "2026-10-22T11:05:00Z");
    items.put("splitMode", "EQUAL");
    assertThat(create(items).status()).isEqualTo(422);
  }

  @Test
  @Tag("REQ-EXP-020")
  @DisplayName("REQ-EXP-020 the same Idempotency-Key and body replays the first expense")
  void idempotency() throws Exception {
    String key = UUID.randomUUID().toString();
    Map<String, Object> body = expense(777, "2024-05-05T10:00:00Z");

    Reply first = createWithKey(body, key);
    Reply second = createWithKey(body, key);

    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.body().get("id")).isEqualTo(first.body().get("id"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from expense where occurred_at = '2024-05-05T10:00:00Z'",
                Integer.class))
        .isEqualTo(1);

    Reply different = createWithKey(expense(778, "2024-05-05T10:00:00Z"), key);
    assertThat(different.status()).isEqualTo(409);
    assertThat(different.body()).containsEntry("title", "Idempotency key reused");

    jdbc.update(
        "update idempotency_key set created_at = now() - interval '25 hours' where key = ?", key);
    Reply expired = createWithKey(expense(778, "2024-05-05T10:00:00Z"), key);
    assertThat(expired.status()).isEqualTo(201);
    assertThat(expired.body().get("id")).isNotEqualTo(first.body().get("id"));
  }

  @Test
  @Tag("REQ-EXP-001")
  @DisplayName(
      "REQ-EXP-001 keyset pagination walks every row once, in order, across equal timestamps")
  void pagination() throws Exception {
    List<String> created = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      String at = i <= 3 ? "2023-02-01T10:00:00Z" : "2023-02-0" + i + "T10:00:00Z";
      created.add((String) create(expense(i * 100L, at)).body().get("id"));
    }
    String window = "from=2023-02-01&to=2023-02-28";

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Reply page =
          send(
              get(
                  "/api/expenses?"
                      + window
                      + "&limit=2"
                      + (cursor == null ? "" : "&cursor=" + cursor)));
      assertThat(page.status()).isEqualTo(200);
      page.list("items").forEach(e -> seen.add((String) e.get("id")));
      cursor = (String) page.body().get("nextCursor");
      pages++;
      assertMoney(page.body().get("totalAmount"), 1500);
    } while (cursor != null && pages < 10);

    assertThat(pages).isEqualTo(3);
    assertThat(seen)
        .hasSize(5)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(created);
    // newest first: the two later days come before the three equal timestamps
    assertThat(seen.subList(0, 2)).containsExactly(created.get(4), created.get(3));

    Reply byAmount = send(get("/api/expenses?" + window + "&sort=amount&limit=50"));
    assertThat(byAmount.list("items"))
        .extracting(ExpensesApiIT::minor)
        .containsExactly(100L, 200L, 300L, 400L, 500L);
    assertThat(byAmount.body().get("nextCursor")).isNull();
    Reply byAmountDesc = send(get("/api/expenses?" + window + "&sort=-amount&limit=2"));
    String next = (String) byAmountDesc.body().get("nextCursor");
    Reply byAmountDescPage2 =
        send(get("/api/expenses?" + window + "&sort=-amount&limit=2&cursor=" + next));
    assertThat(byAmountDescPage2.list("items"))
        .extracting(ExpensesApiIT::minor)
        .containsExactly(300L, 200L);
  }

  @Test
  @Tag("REQ-EXP-001")
  @DisplayName("REQ-EXP-001 from/to are Africa/Cairo calendar days (inclusive)")
  void cairoDayBoundaries() throws Exception {
    // 22:30Z on 10 Mar is 00:30 on 11 Mar in Cairo (UTC+2 in March).
    Reply r = create(expense(4242, "2022-03-10T22:30:00Z"));
    String id = (String) r.body().get("id");
    assertThat(ids(send(get("/api/expenses?from=2022-03-11&to=2022-03-11")))).contains(id);
    assertThat(ids(send(get("/api/expenses?from=2022-03-10&to=2022-03-10")))).doesNotContain(id);
    assertThat(send(get("/api/expenses?from=2022-03-12&to=2022-03-11")).status()).isEqualTo(400);
  }

  /** The Money JSON has exactly the contract keys (no bean-style extras such as "zero"). */
  private static void assertMoney(@Nullable Object actual, long minor) {
    assertThat(actual).isInstanceOf(Map.class);
    Map<?, ?> m = (Map<?, ?>) Objects.requireNonNull(actual);
    assertThat(m.keySet().stream().map(String::valueOf).toList())
        .containsExactlyInAnyOrder("amountMinor", "currency");
    assertThat(((Number) Objects.requireNonNull(m.get("amountMinor"))).longValue())
        .isEqualTo(minor);
    assertThat(m.get("currency")).isEqualTo("EGP");
  }

  private static long minor(Map<String, Object> e) {
    Map<?, ?> amount = (Map<?, ?>) Objects.requireNonNull(e.get("amount"));
    return ((Number) Objects.requireNonNull(amount.get("amountMinor"))).longValue();
  }

  private static List<String> ids(Reply page) {
    return page.list("items").stream().map(e -> (String) e.get("id")).toList();
  }

  @Test
  @Tag("REQ-EXP-001")
  @DisplayName(
      "REQ-EXP-001 filters: category with sub-categories, member, account, type, amount, text")
  void filters() throws Exception {
    Map<String, Object> a = expense(1000, "2021-06-01T10:00:00Z");
    a.put("categoryId", fuel.toString());
    a.put("paidByMemberId", member1.toString());
    a.put("paymentAccountId", account.toString());
    a.put("merchantRaw", "Mobil_100%");
    String fuelId = (String) create(a).body().get("id");
    Map<String, Object> b = expense(2000, "2021-06-02T10:00:00Z");
    b.put("categoryId", groceries.toString());
    b.put("type", "INCOME");
    b.put("notes", "Carrefour run");
    String groceriesId = (String) create(b).body().get("id");
    String w = "from=2021-06-01&to=2021-06-30";

    assertThat(ids(send(get("/api/expenses?" + w + "&categoryId=" + car)))).containsExactly(fuelId);
    assertThat(ids(send(get("/api/expenses?" + w + "&categoryId=" + fuel))))
        .containsExactly(fuelId);
    assertThat(ids(send(get("/api/expenses?" + w + "&memberId=" + member1))))
        .containsExactly(fuelId);
    assertThat(ids(send(get("/api/expenses?" + w + "&accountId=" + account))))
        .containsExactly(fuelId);
    assertThat(ids(send(get("/api/expenses?" + w + "&type=INCOME")))).containsExactly(groceriesId);
    assertThat(ids(send(get("/api/expenses?" + w + "&minAmountMinor=1500"))))
        .containsExactly(groceriesId);
    assertThat(ids(send(get("/api/expenses?" + w + "&maxAmountMinor=1500"))))
        .containsExactly(fuelId);
    assertThat(ids(send(get("/api/expenses?" + w + "&q=carrefour")))).containsExactly(groceriesId);
    assertThat(ids(send(get("/api/expenses?" + w).param("q", "l_100%")))).containsExactly(fuelId);
    assertThat(ids(send(get("/api/expenses?" + w).param("q", "%")))).containsExactly(fuelId);
    assertThat(ids(send(get("/api/expenses?" + w + "&source=SMS")))).isEmpty();
    assertThat(ids(send(get("/api/expenses?" + w + "&source=MANUAL")))).hasSize(2);
    assertThat(ids(send(get("/api/expenses?" + w + "&status=PENDING_REVIEW")))).isEmpty();
    assertThat(ids(send(get("/api/expenses?" + w + "&merchantId=" + UUID.randomUUID())))).isEmpty();
  }

  @Test
  @Tag("REQ-EXP-001")
  @DisplayName("REQ-EXP-001 bad sort, cursor and limit are 400 problems")
  void listValidation() throws Exception {
    assertThat(send(get("/api/expenses?sort=hacker")).status()).isEqualTo(400);
    assertThat(send(get("/api/expenses?cursor=garbage")).status()).isEqualTo(400);
    assertThat(send(get("/api/expenses?limit=0")).status()).isEqualTo(400);
    assertThat(send(get("/api/expenses?limit=201")).status()).isEqualTo(400);
    assertThat(send(get("/api/expenses?from=yesterday")).status()).isEqualTo(400);
    assertThat(send(get("/api/expenses?minAmountMinor=-1")).status()).isEqualTo(400);
    String amountCursor =
        (String) send(get("/api/expenses?sort=amount&limit=1")).body().get("nextCursor");
    assertThat(send(get("/api/expenses?sort=-amount&cursor=" + amountCursor)).status())
        .isEqualTo(400);
  }

  @Test
  @Tag("REQ-EXP-003")
  @DisplayName("REQ-EXP-003 PATCH changes only the given fields and validates them")
  void patchUpdates() throws Exception {
    Map<String, Object> body = expense(1000, "2020-01-01T10:00:00Z");
    body.put("notes", "keep");
    String id = (String) create(body).body().get("id");

    Reply ok =
        send(
            patch("/api/expenses/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"amount\":{\"amountMinor\":1500,\"currency\":\"EGP\"},\"categoryId\":\""
                        + fuel
                        + "\",\"status\":\"PENDING_REVIEW\",\"tags\":[\"a\"]}"));
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body())
        .containsKey("amount")
        .containsEntry("categoryId", fuel.toString())
        .containsEntry("status", "PENDING_REVIEW")
        .containsEntry("notes", "keep");
    assertThat(ok.body().get("tags")).isEqualTo(List.of("a"));
    assertMoney(ok.body().get("amount"), 1500);

    for (String bad :
        List.of(
            "{\"amount\":{\"amountMinor\":0,\"currency\":\"EGP\"}}",
            "{\"occurredAt\":\"2999-01-01T00:00:00Z\"}",
            "{\"categoryId\":\"" + archivedCategory + "\"}",
            "{\"isRecurring\":true}")) {
      assertThat(
              send(patch("/api/expenses/" + id)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(bad))
                  .status())
          .as(bad)
          .isEqualTo(422);
    }
    assertThat(
            send(patch("/api/expenses/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"categoryId\":\"" + otherCategory + "\"}"))
                .status())
        .isEqualTo(404);
    assertThat(
            send(patch("/api/expenses/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .status())
        .isEqualTo(404);
  }

  @Test
  @Tag("REQ-EXP-004")
  @DisplayName("REQ-EXP-004 soft delete hides the expense from get and list but keeps the row")
  void softDelete() throws Exception {
    String id = (String) create(expense(900, "2019-09-09T10:00:00Z")).body().get("id");

    assertThat(send(delete("/api/expenses/" + id)).status()).isEqualTo(204);

    assertThat(send(get("/api/expenses/" + id)).status()).isEqualTo(404);
    assertThat(ids(send(get("/api/expenses?from=2019-09-09&to=2019-09-09")))).doesNotContain(id);
    Reply withDeleted =
        send(get("/api/expenses?from=2019-09-09&to=2019-09-09&includeDeleted=true"));
    assertThat(ids(withDeleted)).contains(id);
    assertThat(withDeleted.list("items").get(0).get("deletedAt")).isNotNull();
    assertThat(send(delete("/api/expenses/" + id)).status()).isEqualTo(404);
    assertThat(
            send(patch("/api/expenses/" + id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .status())
        .isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from expense where id = ?::uuid and deleted_at is not null",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  @Tag("REQ-EXP-001")
  @DisplayName("REQ-EXP-001 TRANSFER is excluded from totalAmount")
  void totalExcludesTransfers() throws Exception {
    create(expense(1000, "2018-01-10T10:00:00Z"));
    Map<String, Object> transfer = expense(5000, "2018-01-11T10:00:00Z");
    transfer.put("type", "TRANSFER");
    create(transfer);
    Reply page = send(get("/api/expenses?from=2018-01-01&to=2018-01-31"));
    assertThat(page.list("items")).hasSize(2);
    assertMoney(page.body().get("totalAmount"), 1000);
  }

  @Test
  @Tag("REQ-EXP-001")
  @DisplayName("REQ-EXP-001 only the current household's rows are visible")
  void householdScoping() throws Exception {
    UUID foreignId = UUID.randomUUID();
    jdbc.update(
        "insert into expense (id, household_id, amount_minor, currency, occurred_at)"
            + " values (?, ?, 100, 'EGP', '2017-01-01T10:00:00Z')",
        foreignId,
        otherHousehold);
    assertThat(send(get("/api/expenses/" + foreignId)).status()).isEqualTo(404);
    assertThat(send(delete("/api/expenses/" + foreignId)).status()).isEqualTo(404);
    assertThat(ids(send(get("/api/expenses?from=2017-01-01&to=2017-01-01")))).isEmpty();
  }

  @Test
  @Tag("REQ-EXP-002")
  @DisplayName("REQ-EXP-002 categories and accounts lists feed the Add sheet")
  void referenceLists() throws Exception {
    Reply categories = send(get("/api/categories"));
    assertThat(categories.status()).isEqualTo(200);
    assertThat(categories.list("items")).hasSize(28); // 29 seeded minus the archived one
    assertThat(categories.list("items"))
        .anySatisfy(
            c -> {
              assertThat(c).containsEntry("nameEn", "Fuel").containsEntry("nameAr", "بنزين");
              assertThat(c.get("parentId")).isEqualTo(car.toString());
            });
    assertThat(send(get("/api/categories?includeArchived=true")).list("items")).hasSize(29);

    Reply accounts = send(get("/api/accounts"));
    assertThat(accounts.status()).isEqualTo(200);
    assertThat(accounts.list("items")).hasSize(1);
    assertThat(accounts.list("items").get(0))
        .containsEntry("id", account.toString())
        .containsEntry("type", "CASH")
        .containsEntry("currency", "EGP")
        .containsEntry("isShared", false);
  }
}
