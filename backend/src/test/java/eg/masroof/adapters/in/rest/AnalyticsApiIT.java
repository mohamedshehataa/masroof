package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Analytics endpoints end to end against real Postgres (REQ-AN-001, -002, -004, -005, -006). */
@SpringBootTest(properties = "masroof.dev.single-household=true")
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsApiIT {

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
    groceries = categoryId(household, "groceries");
    car = categoryId(household, "car");
    fuel = categoryId(household, "car.fuel");

    // February 2026: 200.00 groceries.
    add(household, "EXPENSE", 20_000, "EGP", "2026-02-10T10:00:00Z", "CONFIRMED", groceries, null);
    // March 2026 (UTC+2): counted rows.
    add(household, "EXPENSE", 10_000, "EGP", "2026-03-05T10:00:00Z", "CONFIRMED", groceries, null);
    add(
        household,
        "EXPENSE",
        1_000,
        "EGP",
        "2026-03-06T10:00:00Z",
        "PENDING_REVIEW",
        groceries,
        null);
    // 22:30Z on 14 Mar is 00:30 on Sunday 15 Mar in Cairo.
    add(household, "EXPENSE", 3_000, "EGP", "2026-03-14T22:30:00Z", "CONFIRMED", fuel, "Shell");
    add(household, "EXPENSE", 2_000, "EGP", "2026-03-20T10:00:00Z", "CONFIRMED", car, " shell ");
    add(household, "REFUND", 500, "EGP", "2026-03-21T10:00:00Z", "CONFIRMED", car, null);
    add(household, "INCOME", 100_000, "EGP", "2026-03-01T10:00:00Z", "CONFIRMED", null, null);
    // March rows that must not count by default.
    add(household, "TRANSFER", 99_999, "EGP", "2026-03-07T10:00:00Z", "CONFIRMED", null, null);
    add(
        household,
        "CASH_WITHDRAWAL",
        7_777,
        "EGP",
        "2026-03-08T10:00:00Z",
        "CONFIRMED",
        null,
        null);
    add(household, "EXPENSE", 8_888, "EGP", "2026-03-09T10:00:00Z", "IGNORED", groceries, null);
    add(
        household,
        "EXPENSE",
        8_889,
        "EGP",
        "2026-03-09T11:00:00Z",
        "POSSIBLE_DUPLICATE",
        groceries,
        null);
    add(household, "EXPENSE", 5_555, "USD", "2026-03-10T10:00:00Z", "CONFIRMED", groceries, null);
    UUID deleted =
        add(
            household,
            "EXPENSE",
            6_666,
            "EGP",
            "2026-03-11T10:00:00Z",
            "CONFIRMED",
            groceries,
            null);
    jdbc.update("update expense set deleted_at = now() where id = ?", deleted);
    add(other, "EXPENSE", 4_444, "EGP", "2026-03-12T10:00:00Z", "CONFIRMED", null, null);
    // 21:30Z on 14 Jul is 00:30 on 15 Jul in Cairo summer time (UTC+3).
    add(household, "EXPENSE", 1_234, "EGP", "2026-07-14T21:30:00Z", "CONFIRMED", groceries, null);
    // Right now, so the "current" period always has data.
    add(
        household,
        "EXPENSE",
        12_345,
        "EGP",
        Instant.now().toString(),
        "CONFIRMED",
        groceries,
        null);
  }

  private UUID categoryId(UUID h, String key) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "select id from category where household_id = ? and reference_key = ?",
            UUID.class,
            h,
            key));
  }

  private UUID add(
      UUID h,
      String type,
      long minor,
      String currency,
      String at,
      String status,
      @Nullable UUID category,
      @Nullable String merchant) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into expense (id, household_id, type, amount_minor, currency, occurred_at,"
            + " category_id, merchant_raw, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        h,
        type,
        minor,
        currency,
        Timestamp.from(Instant.parse(at)),
        category,
        merchant,
        status);
    return id;
  }

  private record Reply(int status, Map<String, Object> body) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(String key) {
      return (List<Map<String, Object>>) Objects.requireNonNull(body.get(key));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String key) {
      return (Map<String, Object>) Objects.requireNonNull(body.get(key));
    }
  }

  private Reply fetch(String url) throws Exception {
    MockHttpServletResponse response = mvc.perform(get(url)).andReturn().getResponse();
    String text = response.getContentAsString();
    Map<String, Object> body =
        text.isEmpty()
            ? Map.of()
            : JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
    return new Reply(response.getStatus(), body);
  }

  private static long minor(@Nullable Object money) {
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) Objects.requireNonNull(money);
    assertThat(m).containsEntry("currency", "EGP");
    return ((Number) Objects.requireNonNull(m.get("amountMinor"))).longValue();
  }

  @Test
  @Tag("REQ-AN-001")
  @DisplayName("REQ-AN-001 summary counts only live, confirmed or pending, base-currency spend")
  void summaryTotals() throws Exception {
    Reply r = fetch("/api/analytics/summary?period=2026-03");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.map("period"))
        .containsEntry("label", "2026-03")
        .containsEntry("from", "2026-03-01")
        .containsEntry("to", "2026-03-31");
    assertThat(minor(r.body().get("total"))).isEqualTo(15_500);
    assertThat(minor(r.body().get("previousTotal"))).isEqualTo(20_000);
    assertThat(r.body()).containsEntry("deltaPercentBps", -2250);
    assertThat(minor(r.body().get("income"))).isEqualTo(100_000);
    assertThat(r.body()).containsEntry("pendingReviewCount", 1).containsEntry("daysLeft", 0);
    assertThat(minor(r.body().get("projectedTotal"))).isEqualTo(15_500);
    assertThat(minor(r.body().get("dailyAverage"))).isEqualTo(500);
    assertThat(r.list("topCategories")).hasSize(2);
  }

  @Test
  @Tag("REQ-AN-001")
  @DisplayName("REQ-AN-001 includeCashWithdrawals=true adds cash withdrawals, never transfers")
  void cashWithdrawalsOptIn() throws Exception {
    Reply r = fetch("/api/analytics/summary?period=2026-03&includeCashWithdrawals=true");
    assertThat(minor(r.body().get("total"))).isEqualTo(15_500 + 7_777);
  }

  @Test
  @Tag("REQ-AN-001")
  @DisplayName("REQ-AN-001 current period has the row inserted now and a projection")
  void currentPeriod() throws Exception {
    Reply r = fetch("/api/analytics/summary");
    assertThat(r.status()).isEqualTo(200);
    assertThat(minor(r.body().get("total"))).isGreaterThanOrEqualTo(12_345);
    assertThat(minor(r.body().get("projectedTotal"))).isGreaterThanOrEqualTo(12_345);
    Reply f = fetch("/api/analytics/forecast");
    assertThat(f.status()).isEqualTo(200);
    assertThat(minor(f.body().get("spentSoFar"))).isGreaterThanOrEqualTo(12_345);
    assertThat(minor(f.body().get("weekdayAware"))).isGreaterThanOrEqualTo(12_345);
    assertThat(f.list("upcomingRecurring")).isEmpty();
  }

  @Test
  @Tag("REQ-AN-001")
  @DisplayName("REQ-AN-001 month_start_day 25 moves the period boundaries")
  void monthStartDay25() throws Exception {
    jdbc.update("update household set month_start_day = 25 where id = ?", household);
    try {
      Reply r = fetch("/api/analytics/summary?period=2026-03");
      assertThat(r.map("period"))
          .containsEntry("from", "2026-03-25")
          .containsEntry("to", "2026-04-24");
      // None of the March seed rows fall on or after the 25th.
      assertThat(minor(r.body().get("total"))).isZero();
      Reply feb = fetch("/api/analytics/summary?period=2026-02");
      assertThat(feb.map("period")).containsEntry("to", "2026-03-24");
      assertThat(minor(feb.body().get("total"))).isEqualTo(15_500);
    } finally {
      jdbc.update("update household set month_start_day = 1 where id = ?", household);
    }
  }

  @Test
  @Tag("REQ-AN-002")
  @DisplayName("REQ-AN-002 categories roll sub-categories up, rank, and compare with last month")
  void byCategory() throws Exception {
    Reply r = fetch("/api/analytics/by-category?period=2026-03");

    assertThat(r.status()).isEqualTo(200);
    assertThat(minor(r.body().get("total"))).isEqualTo(15_500);
    List<Map<String, Object>> items = r.list("items");
    assertThat(items)
        .extracting(i -> i.get("categoryId"))
        .containsExactly(groceries.toString(), car.toString());
    assertThat(minor(items.get(0).get("total"))).isEqualTo(11_000);
    assertThat(items.get(0))
        .containsEntry("shareBps", 7097)
        .containsEntry("deltaPercentBps", -4500)
        .containsEntry("count", 2);
    assertThat(minor(items.get(0).get("previousTotal"))).isEqualTo(20_000);
    assertThat(minor(items.get(1).get("total"))).isEqualTo(4_500);
    assertThat(items.get(1)).containsEntry("shareBps", 2903).containsEntry("count", 3);
    assertThat(items.get(1).get("deltaPercentBps")).isNull();
    assertThat(items.get(0).get("nameAr")).isNotNull();
  }

  @Test
  @Tag("REQ-AN-002")
  @DisplayName("REQ-AN-002 parentId drills into the category and its children")
  void drillDown() throws Exception {
    Reply r = fetch("/api/analytics/by-category?period=2026-03&parentId=" + car);

    assertThat(minor(r.body().get("total"))).isEqualTo(4_500);
    assertThat(r.list("items"))
        .extracting(i -> i.get("categoryId"))
        .containsExactly(fuel.toString(), car.toString());
  }

  @Test
  @Tag("REQ-AN-004")
  @DisplayName("REQ-AN-004 merchants group case-insensitively with visits and average ticket")
  void merchants() throws Exception {
    Reply r = fetch("/api/analytics/by-merchant?period=2026-03&limit=5");

    assertThat(r.status()).isEqualTo(200);
    List<Map<String, Object>> items = r.list("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).containsEntry("name", "Shell").containsEntry("visits", 2);
    assertThat(minor(items.get(0).get("total"))).isEqualTo(5_000);
    assertThat(minor(items.get(0).get("averageTicket"))).isEqualTo(2_500);
  }

  @Test
  @Tag("REQ-AN-006")
  @DisplayName("REQ-AN-006 heatmap uses Cairo days and hours, Saturday-first, across DST")
  void heatmap() throws Exception {
    Reply march = fetch("/api/analytics/heatmap?period=2026-03");
    List<Map<String, Object>> days = march.list("days");
    assertThat(days).hasSize(31);
    Map<String, Object> day15 = days.get(14);
    assertThat(day15).containsEntry("date", "2026-03-15").containsEntry("count", 1);
    assertThat(minor(day15.get("total"))).isEqualTo(3_000);
    assertThat(minor(days.get(13).get("total"))).isZero();
    @SuppressWarnings("unchecked")
    List<List<Map<String, Object>>> matrix =
        (List<List<Map<String, Object>>>) Objects.requireNonNull(march.body().get("weekdayHour"));
    assertThat(matrix).hasSize(7);
    // 15 March 2026 is a Sunday: index 1; 00:30 Cairo: hour 0.
    assertThat(minor(matrix.get(1).get(0))).isEqualTo(3_000);

    Reply july = fetch("/api/analytics/heatmap?period=2026-07");
    Map<String, Object> jul15 = july.list("days").get(14);
    assertThat(jul15).containsEntry("date", "2026-07-15");
    assertThat(minor(jul15.get("total"))).isEqualTo(1_234);
  }

  @Test
  @Tag("REQ-AN-005")
  @DisplayName("REQ-AN-005 trend returns one point per month, oldest first, ending now")
  void trend() throws Exception {
    Reply r = fetch("/api/analytics/trend?months=6");

    assertThat(r.status()).isEqualTo(200);
    List<Map<String, Object>> points = r.list("points");
    assertThat(points).hasSize(6);
    Map<String, Object> last = points.get(5);
    Map<String, Object> current = fetch("/api/analytics/summary").map("period");
    @SuppressWarnings("unchecked")
    Map<String, Object> lastPeriod = (Map<String, Object>) last.get("period");
    assertThat(lastPeriod).containsEntry("label", current.get("label"));
    assertThat(minor(last.get("total"))).isGreaterThanOrEqualTo(12_345);
    assertThat(points.get(0).get("byCategory")).isNotNull();
  }

  @Test
  @Tag("REQ-AN-005")
  @DisplayName("REQ-AN-005 trend covering March carries income and the savings rate")
  void trendIncome() throws Exception {
    Reply r = fetch("/api/analytics/trend?months=36");
    Map<String, Object> march =
        r.list("points").stream()
            .filter(
                p -> {
                  @SuppressWarnings("unchecked")
                  Map<String, Object> period =
                      (Map<String, Object>) Objects.requireNonNull(p.get("period"));
                  return "2026-03".equals(period.get("label"));
                })
            .findFirst()
            .orElseThrow();
    assertThat(minor(march.get("income"))).isEqualTo(100_000);
    assertThat(march).containsEntry("savingsRateBps", 8450);
  }

  @Test
  @Tag("REQ-AN-001")
  @DisplayName("REQ-AN-001 malformed parameters are 400 problem+json against the field")
  void validation() throws Exception {
    for (String url :
        List.of(
            "/api/analytics/summary?period=2026-13",
            "/api/analytics/summary?period=banana",
            "/api/analytics/by-category?period=2026-W99",
            "/api/analytics/trend?months=0",
            "/api/analytics/trend?months=37",
            "/api/analytics/by-merchant?limit=0")) {
      assertThat(fetch(url).status()).as(url).isEqualTo(400);
    }
    Reply r = fetch("/api/analytics/summary?period=2026-13");
    assertThat(r.body()).containsKey("errors");
    assertThat(r.list("errors")).extracting(e -> e.get("field")).containsExactly("period");
  }

  @Test
  @Tag("REQ-AN-003")
  @DisplayName("REQ-AN-003 operations declared but not built answer 501 problem+json")
  void notBuilt() throws Exception {
    assertThat(fetch("/api/analytics/by-member").status()).isEqualTo(501);
    assertThat(fetch("/api/analytics/insights").status()).isEqualTo(501);
  }
}
