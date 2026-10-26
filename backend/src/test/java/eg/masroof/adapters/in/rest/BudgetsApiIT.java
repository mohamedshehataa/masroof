package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Instant;
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

/** Budgets CRUD, scoping and progress against real Postgres (REQ-AN-008). */
@SpringBootTest(properties = "masroof.dev.single-household=true")
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetsApiIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  UUID household;
  UUID other;
  UUID groceries;
  UUID car;
  UUID fuel;
  UUID otherCategory;

  @BeforeAll
  void seed() {
    household = UUID.randomUUID();
    other = UUID.randomUUID();
    jdbc.update(
        "insert into household (id, name, created_at) values (?, 'Test', now() - interval '1 day')",
        household);
    jdbc.update("insert into household (id, name) values (?, 'Other')", other);
    jdbc.queryForList("select seed_household_categories(?)", household);
    jdbc.queryForList("select seed_household_categories(?)", other);
    groceries = category(household, "groceries");
    car = category(household, "car");
    fuel = category(household, "car.fuel");
    otherCategory = category(other, "groceries");

    // March 2026 (same fixture rules as the analytics tests).
    add("EXPENSE", 10_000, "EGP", "2026-03-05T10:00:00Z", "CONFIRMED", groceries);
    add("EXPENSE", 1_000, "EGP", "2026-03-06T10:00:00Z", "PENDING_REVIEW", groceries);
    add("EXPENSE", 3_000, "EGP", "2026-03-14T22:30:00Z", "CONFIRMED", fuel);
    add("EXPENSE", 2_000, "EGP", "2026-03-20T10:00:00Z", "CONFIRMED", car);
    add("REFUND", 500, "EGP", "2026-03-21T10:00:00Z", "CONFIRMED", car);
    // Must not count: transfer, cash withdrawal, ignored, duplicate, foreign currency, income.
    add("TRANSFER", 99_999, "EGP", "2026-03-07T10:00:00Z", "CONFIRMED", null);
    add("CASH_WITHDRAWAL", 7_777, "EGP", "2026-03-08T10:00:00Z", "CONFIRMED", null);
    add("EXPENSE", 8_888, "EGP", "2026-03-09T10:00:00Z", "IGNORED", groceries);
    add("EXPENSE", 8_889, "EGP", "2026-03-09T11:00:00Z", "POSSIBLE_DUPLICATE", groceries);
    add("EXPENSE", 5_555, "USD", "2026-03-10T10:00:00Z", "CONFIRMED", groceries);
    add("INCOME", 100_000, "EGP", "2026-03-01T10:00:00Z", "CONFIRMED", null);
    // February 2026: 200.00 groceries (previous period for rollover).
    add("EXPENSE", 20_000, "EGP", "2026-02-10T10:00:00Z", "CONFIRMED", groceries);
  }

  private UUID category(UUID h, String key) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "select id from category where household_id = ? and reference_key = ?",
            UUID.class,
            h,
            key));
  }

  private void add(
      String type, long minor, String currency, String at, String status, @Nullable UUID cat) {
    jdbc.update(
        "insert into expense (id, household_id, type, amount_minor, currency, occurred_at,"
            + " category_id, status) values (?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        household,
        type,
        minor,
        currency,
        Timestamp.from(Instant.parse(at)),
        cat,
        status);
  }

  private record Reply(int status, Map<String, Object> body) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(String key) {
      return (List<Map<String, Object>>) Objects.requireNonNull(body.get(key));
    }

    String id() {
      return String.valueOf(body.get("id"));
    }
  }

  private Reply send(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
    String text = response.getContentAsString();
    Map<String, Object> body =
        text.isEmpty()
            ? Map.of()
            : JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
    return new Reply(response.getStatus(), body);
  }

  private Reply create(String json) throws Exception {
    return send(post("/api/budgets").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private static String input(@Nullable UUID cat, long minor, String extra) {
    return "{\"categoryId\":"
        + (cat == null ? "null" : "\"" + cat + "\"")
        + ",\"amount\":{\"amountMinor\":"
        + minor
        + ",\"currency\":\"EGP\"}"
        + extra
        + "}";
  }

  private void clear() {
    jdbc.update("delete from budget");
  }

  private static long minor(@Nullable Object money) {
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) Objects.requireNonNull(money);
    return ((Number) Objects.requireNonNull(m.get("amountMinor"))).longValue();
  }

  private Map<String, Object> statusOf(UUID budgetId, String period) throws Exception {
    Reply r = send(get("/api/analytics/budgets?period=" + period));
    assertThat(r.status()).isEqualTo(200);
    return r.list("items").stream()
        .filter(
            i -> {
              @SuppressWarnings("unchecked")
              Map<String, Object> b = (Map<String, Object>) Objects.requireNonNull(i.get("budget"));
              return budgetId.toString().equals(b.get("id"));
            })
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("REQ-AN-008 create, read, list, update, delete")
  void crud() throws Exception {
    clear();
    Reply created = create(input(groceries, 50_000, ",\"rollover\":true"));
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.body())
        .containsEntry("categoryId", groceries.toString())
        .containsEntry("period", "MONTHLY")
        .containsEntry("rollover", true)
        .containsEntry("householdId", household.toString());
    assertThat(created.body().get("alertThresholds")).isEqualTo(List.of(50, 80, 100));
    assertThat(minor(created.body().get("amount"))).isEqualTo(50_000);

    assertThat(send(get("/api/budgets/" + created.id())).status()).isEqualTo(200);
    assertThat(send(get("/api/budgets")).list("items")).hasSize(1);

    Reply updated =
        send(
            patch("/api/budgets/" + created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"amount\":{\"amountMinor\":70000,\"currency\":\"EGP\"},\"alertThresholds\":[90,60]}"));
    assertThat(updated.status()).isEqualTo(200);
    assertThat(minor(updated.body().get("amount"))).isEqualTo(70_000);
    assertThat(updated.body().get("alertThresholds")).isEqualTo(List.of(60, 90));
    assertThat(updated.body()).containsEntry("rollover", true);

    assertThat(send(delete("/api/budgets/" + created.id())).status()).isEqualTo(204);
    assertThat(send(get("/api/budgets/" + created.id())).status()).isEqualTo(404);
    assertThat(send(delete("/api/budgets/" + created.id())).status()).isEqualTo(404);
  }

  @Test
  @DisplayName("REQ-AN-008 total budget has a null category; duplicates conflict")
  void totalAndDuplicate() throws Exception {
    clear();
    Reply total = create(input(null, 90_000, ""));
    assertThat(total.status()).isEqualTo(201);
    assertThat(total.body()).containsKey("categoryId");
    assertThat(total.body().get("categoryId")).isNull();
    assertThat(create(input(null, 1_000, "")).status()).isEqualTo(409);
    assertThat(create(input(null, 1_000, ",\"period\":\"WEEKLY\"")).status()).isEqualTo(201);
  }

  @Test
  @DisplayName("REQ-AN-008 validation failures are 400 problems without echoing values")
  void validation() throws Exception {
    clear();
    assertThat(create("{}").status()).isEqualTo(400);
    assertThat(create(input(groceries, 0, "")).status()).isEqualTo(400);
    assertThat(create(input(groceries, -5, "")).status()).isEqualTo(400);
    assertThat(create(input(groceries, 2_000_000_000_000L, "")).status()).isEqualTo(400);
    assertThat(create(input(groceries, 100, ",\"alertThresholds\":[50,50]")).status())
        .isEqualTo(400);
    assertThat(create(input(groceries, 100, ",\"alertThresholds\":[0]")).status()).isEqualTo(400);
    assertThat(create(input(groceries, 100, ",\"period\":\"CUSTOM\"")).status()).isEqualTo(422);
    assertThat(
            create(
                    "{\"categoryId\":\""
                        + groceries
                        + "\",\"amount\":{\"amountMinor\":100,\"currency\":\"USD\"}}")
                .status())
        .isEqualTo(400);
    assertThat(create(input(UUID.randomUUID(), 100, "")).status()).isEqualTo(400);
    assertThat(send(get("/api/analytics/budgets?period=bogus")).status()).isEqualTo(400);
    assertThat(send(get("/api/budgets/not-a-uuid")).status()).isEqualTo(400);
  }

  @Test
  @DisplayName("REQ-AN-008 another household's budgets and categories are invisible (IDOR)")
  void idor() throws Exception {
    clear();
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "insert into budget (id, household_id, category_id, period, amount_minor, currency)"
            + " values (?, ?, ?, 'MONTHLY', 12345, 'EGP')",
        foreign,
        other,
        otherCategory);

    assertThat(send(get("/api/budgets")).list("items")).isEmpty();
    assertThat(send(get("/api/budgets/" + foreign)).status()).isEqualTo(404);
    assertThat(
            send(patch("/api/budgets/" + foreign)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":{\"amountMinor\":1,\"currency\":\"EGP\"}}"))
                .status())
        .isEqualTo(404);
    assertThat(send(delete("/api/budgets/" + foreign)).status()).isEqualTo(404);
    assertThat(create(input(otherCategory, 100, "")).status()).isEqualTo(400);
    assertThat(send(get("/api/analytics/budgets")).list("items")).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select amount_minor from budget where id = ?", Long.class, foreign))
        .isEqualTo(12_345L);
  }

  @Test
  @DisplayName("REQ-AN-008 progress matches hand-computed March 2026 spend")
  void statusMath() throws Exception {
    clear();
    UUID food = UUID.fromString(create(input(groceries, 22_000, "")).id());
    UUID wheels = UUID.fromString(create(input(car, 9_000, ",\"alertThresholds\":[50,100]")).id());
    UUID all = UUID.fromString(create(input(null, 15_000, "")).id());

    // Groceries: 10,000 + 1,000 pending = 11,000 of 22,000 = 50.00%.
    Map<String, Object> g = statusOf(food, "2026-03");
    assertThat(minor(g.get("spent"))).isEqualTo(11_000);
    assertThat(minor(g.get("remaining"))).isEqualTo(11_000);
    assertThat(g).containsEntry("progressBps", 5_000).containsEntry("daysLeft", 0);
    assertThat(g.get("alertsFired")).isEqualTo(List.of(50));

    // Car incl. sub-category fuel: 3,000 + 2,000 - 500 refund = 4,500 of 9,000 = 50%.
    Map<String, Object> c = statusOf(wheels, "2026-03");
    assertThat(minor(c.get("spent"))).isEqualTo(4_500);
    assertThat(c).containsEntry("progressBps", 5_000);
    assertThat(c.get("alertsFired")).isEqualTo(List.of(50));

    // Total: 11,000 + 4,500 = 15,500 of 15,000: no transfer, cash, ignored, duplicate, USD.
    Map<String, Object> t = statusOf(all, "2026-03");
    assertThat(minor(t.get("spent"))).isEqualTo(15_500);
    assertThat(minor(t.get("remaining"))).isEqualTo(-500);
    assertThat(t).containsEntry("progressBps", 10_333).containsEntry("onTrack", false);
    assertThat(t.get("alertsFired")).isEqualTo(List.of(50, 80, 100));
    @SuppressWarnings("unchecked")
    Map<String, Object> range = (Map<String, Object>) t.get("periodRange");
    assertThat(range).containsEntry("from", "2026-03-01").containsEntry("to", "2026-03-31");
  }

  @Test
  @DisplayName("REQ-AN-008 rollover adds last month's leftover to the limit")
  void rollover() throws Exception {
    clear();
    UUID id = UUID.fromString(create(input(groceries, 30_000, ",\"rollover\":true")).id());
    jdbc.update("update budget set created_at = '2026-01-01T00:00:00Z' where id = ?", id);

    // February spent 20,000 of 30,000 -> 10,000 rolls into March: limit 40,000, spent 11,000.
    Map<String, Object> s = statusOf(id, "2026-03");
    assertThat(minor(s.get("rolledOver"))).isEqualTo(10_000);
    assertThat(minor(s.get("remaining"))).isEqualTo(29_000);
    assertThat(s).containsEntry("progressBps", 2_750);
  }

  @Test
  @DisplayName("REQ-AN-008 the summary reports the remaining total budget")
  void summaryRemainingBudget() throws Exception {
    clear();
    Reply none = send(get("/api/analytics/summary?period=2026-03"));
    assertThat(none.body().get("remainingBudget")).isNull();
    create(input(null, 20_000, ""));
    Reply r = send(get("/api/analytics/summary?period=2026-03"));
    // 20,000 limit - 15,500 spent in March.
    assertThat(minor(r.body().get("remainingBudget"))).isEqualTo(4_500);
  }
}
