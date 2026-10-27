package eg.masroof.application.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eg.masroof.application.analytics.AnalyticsQueries.CategoryRef;
import eg.masroof.application.analytics.AnalyticsReports.CategoryLine;
import eg.masroof.domain.analytics.AnalyticsPeriod;
import eg.masroof.domain.expense.BusinessCalendar;
import eg.masroof.domain.money.Money;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The golden file {@code fixtures/analytics/parity-v1.json} equals what {@link AnalyticsService}
 * computes on real Postgres (ADR-0009 D2 parity test 3). Regenerate the {@code scenarios} part only
 * with {@code MASROOF_PARITY_GENERATE=1}; the file is otherwise never edited.
 */
@SpringBootTest(properties = "masroof.dev.single-household=true")
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsParityIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final Path FILE = Path.of("..", "fixtures", "analytics", "parity-v1.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ZoneId CAIRO = BusinessCalendar.CAIRO;
  private static final Set<String> TYPES =
      Set.of("EXPENSE", "INCOME", "REFUND", "TRANSFER", "CASH_WITHDRAWAL");
  private static final Set<String> STATUSES =
      Set.of("CONFIRMED", "PENDING_REVIEW", "POSSIBLE_DUPLICATE", "IGNORED");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final int MERCHANT_LIMIT = 50;

  /** The two sides of both 2026 Cairo DST changes (spring forward 24 Apr, fall back 29 Oct). */
  private static final List<String> DST_INSTANTS =
      List.of(
          "2026-04-23T21:59:59Z",
          "2026-04-23T22:00:00Z",
          "2026-10-29T20:59:59Z",
          "2026-10-29T21:00:00Z");

  /** A clock the test moves to each {@code today}. */
  static final class MovableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

    void setNow(Instant instant) {
      now.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return java.util.Objects.requireNonNull(now.get());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockOverride {
    @Bean
    @Primary
    MovableClock movableClock() {
      return new MovableClock();
    }
  }

  @Autowired AnalyticsService analytics;
  @Autowired JdbcTemplate jdbc;
  @Autowired MovableClock clock;

  UUID household;
  final Map<String, UUID> categoryIds = new HashMap<>();
  final Map<UUID, String> categoryKeys = new HashMap<>();

  @BeforeAll
  void createHousehold() {
    household = UUID.randomUUID();
    jdbc.update(
        "insert into household (id, name, created_at) values (?, 'Parity', '2000-01-01T00:00:00Z')",
        household);
    jdbc.queryForList("select seed_household_categories(?)", household);
    jdbc.query(
        "select id, reference_key from category where household_id = ? and reference_key is not null",
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          String key = rs.getString("reference_key");
          categoryIds.put(key, id);
          categoryKeys.put(id, key);
        },
        household);
  }

  // ---------------------------------------------------------------- the main assertion

  @Test
  @Tag("REQ-AN-143")
  @Tag("REQ-AN-144")
  @DisplayName("REQ-AN-143 the golden file equals what AnalyticsService computes")
  void goldenFileEqualsService() throws IOException {
    JsonNode file = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
    validateSeed(file);
    checkCoverage(file);
    checkCases(file);
    assertCatalogMatchesDatabase(file);
    loadSeed(file);

    ArrayNode computed = compute(file);
    if (System.getenv("MASROOF_PARITY_GENERATE") != null) {
      ((ObjectNode) file).set("scenarios", computed);
      Files.writeString(FILE, render(file) + "\n", StandardCharsets.UTF_8);
      return;
    }
    List<String> problems = compare(file.get("scenarios"), computed);
    assertThat(problems).as("differences between parity-v1.json and AnalyticsService").isEmpty();
  }

  // ---------------------------------------------------------------- unhappy paths

  @Test
  @Tag("REQ-AN-143")
  @DisplayName("REQ-AN-143 a one-piaster change in a copy fails naming the output and the field")
  void onePiasterChangeIsDetected() throws IOException {
    JsonNode file = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
    ArrayNode scenarios = (ArrayNode) file.get("scenarios");
    assertThat(scenarios.size()).as("scenarios in the file").isGreaterThanOrEqualTo(12);
    JsonNode copy = file.deepCopy();
    ObjectNode summary = (ObjectNode) copy.get("scenarios").get(0).get("outputs").get("summary");
    summary.put("total", summary.get("total").asLong() + 1);

    List<String> problems = compare(copy.get("scenarios"), file.get("scenarios"));

    assertThat(problems).hasSize(1);
    assertThat(problems.get(0)).contains("output 'summary'").contains("field 'total'");
  }

  @Test
  @Tag("REQ-AN-143")
  @DisplayName("REQ-AN-143 an order change is a difference (ordering is not lenient)")
  void orderChangeIsDetected() throws IOException {
    JsonNode file = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
    JsonNode copy = file.deepCopy();
    ArrayNode items = null;
    for (JsonNode scenario : copy.get("scenarios")) {
      ArrayNode candidate = (ArrayNode) scenario.get("outputs").get("merchants").get("items");
      if (candidate.size() >= 2) {
        items = candidate;
        break;
      }
    }
    assertThat(items).isNotNull();
    java.util.Objects.requireNonNull(items);
    JsonNode first = items.get(0);
    items.set(0, items.get(1));
    items.set(1, first);

    List<String> problems = compare(copy.get("scenarios"), file.get("scenarios"));

    assertThat(problems).isNotEmpty();
    assertThat(problems.get(0)).contains("output 'merchants'").contains("items[0]");
  }

  @Test
  @Tag("REQ-AN-143")
  @DisplayName("REQ-AN-143 malformed seed rows fail before the service is called")
  void malformedSeedRowsAreRejected() throws IOException {
    JsonNode file = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
    validateSeed(file);

    assertRejected(file, "amountMinor", JSON.valueToTree(12.5), "non-integer amount");
    assertRejected(file, "amountMinor", JSON.valueToTree("100"), "non-integer amount");
    assertRejected(file, "amountMinor", JSON.valueToTree(0), "amount below one piaster");
    assertRejected(file, "type", JSON.valueToTree("BONUS"), "unknown type");
    assertRejected(file, "status", JSON.valueToTree("REJECTED"), "unknown status");
    assertRejected(file, "currency", JSON.valueToTree("EG"), "currency not 3 letters");
    assertRejected(file, "currency", JSON.valueToTree("egp"), "currency not upper case");
    assertRejected(file, "occurredAt", JSON.valueToTree("2026-04-01"), "not an instant");
    assertRejected(file, "categoryKey", JSON.valueToTree("nope"), "unknown category");
  }

  private static void assertRejected(JsonNode file, String field, JsonNode value, String why) {
    JsonNode copy = file.deepCopy();
    ((ObjectNode) copy.get("expenses").get(0)).set(field, value);
    assertThatThrownBy(() -> validateSeed(copy))
        .as(why)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seed row r001")
        .hasMessageContaining(field);
  }

  @Test
  @Tag("REQ-AN-144")
  @DisplayName("REQ-AN-144 a seed that lacks a listed case fails naming the case")
  void missingCaseIsNamed() throws IOException {
    JsonNode file = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
    checkCoverage(file);
    Map<String, Predicate<JsonNode>> removals = new LinkedHashMap<>();
    removals.put("transfer", r -> "TRANSFER".equals(text(r, "type")));
    removals.put("cash-withdrawal", r -> "CASH_WITHDRAWAL".equals(text(r, "type")));
    removals.put("refund", r -> "REFUND".equals(text(r, "type")));
    removals.put("foreign-currency", r -> !"EGP".equals(text(r, "currency")));
    removals.put("soft-deleted", r -> r.get("deleted").asBoolean());
    removals.put("income", r -> "INCOME".equals(text(r, "type")));
    removals.put(
        "child-category",
        r ->
            text(r, "categoryKey") != null && String.valueOf(text(r, "categoryKey")).contains("."));
    removals.put("dst-april-before", r -> "2026-04-23T21:59:59Z".equals(text(r, "occurredAt")));
    for (Map.Entry<String, Predicate<JsonNode>> removal : removals.entrySet()) {
      ObjectNode copy = (ObjectNode) file.deepCopy();
      ArrayNode kept = JSON.createArrayNode();
      for (JsonNode row : copy.get("expenses")) {
        if (!removal.getValue().test(row)) {
          kept.add(row);
        }
      }
      copy.set("expenses", kept);
      assertThatThrownBy(() -> checkCoverage(copy))
          .as("without %s", removal.getKey())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("'" + removal.getKey() + "'");
    }
  }

  @Test
  @Tag("REQ-AN-144")
  @DisplayName("REQ-AN-144 the readme states purpose, version 1 and that the file never changes")
  void readmeStatesTheRules() throws IOException {
    JsonNode file = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
    String readme = text(file, "_readme");
    assertThat(readme)
        .isNotNull()
        .contains("version 1")
        .contains("never changes")
        .contains("parity-v2.json");
    assertThat(file.get("version").asInt()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- seed validation

  static void validateSeed(JsonNode root) {
    Set<String> keys = new LinkedHashSet<>();
    for (JsonNode c : root.get("categories")) {
      keys.add(text(c, "key"));
    }
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode row : root.get("expenses")) {
      String id = text(row, "id");
      String where = "seed row " + id + ": ";
      require(id != null && ids.add(id), where + "id missing or duplicated");
      require(TYPES.contains(text(row, "type")), where + "type is not a known enum value");
      require(STATUSES.contains(text(row, "status")), where + "status is not a known enum value");
      String currency = text(row, "currency");
      require(
          currency != null && CURRENCY.matcher(currency).matches(),
          where + "currency must be 3 upper-case letters");
      JsonNode amount = row.get("amountMinor");
      require(
          amount != null
              && amount.isIntegralNumber()
              && amount.canConvertToLong()
              && amount.asLong() > 0,
          where + "amountMinor must be an integer of at least 1");
      try {
        Instant.parse(text(row, "occurredAt"));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            where + "occurredAt must be a UTC instant like 2026-04-01T09:00:00Z");
      }
      String category = text(row, "categoryKey");
      require(
          category == null || keys.contains(category), where + "categoryKey is not in categories");
      require(
          row.get("deleted") != null && row.get("deleted").isBoolean(),
          where + "deleted must be a boolean");
    }
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }

  private static @Nullable String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() || !v.isString() ? null : v.stringValue();
  }

  // ---------------------------------------------------------------- REQ-AN-144 coverage

  static void checkCoverage(JsonNode root) {
    List<JsonNode> rows = new ArrayList<>();
    root.get("expenses").forEach(rows::add);
    Map<String, Boolean> cases = new LinkedHashMap<>();
    cases.put("transfer", any(rows, r -> "TRANSFER".equals(text(r, "type"))));
    cases.put("cash-withdrawal", any(rows, r -> "CASH_WITHDRAWAL".equals(text(r, "type"))));
    cases.put("refund", any(rows, r -> "REFUND".equals(text(r, "type"))));
    cases.put("income", any(rows, r -> "INCOME".equals(text(r, "type")) && isLiveEgp(r)));
    cases.put("foreign-currency", any(rows, r -> "USD".equals(text(r, "currency"))));
    cases.put(
        "pending-review",
        any(
            rows,
            r -> "PENDING_REVIEW".equals(text(r, "status")) && !r.get("deleted").asBoolean()));
    cases.put("ignored", any(rows, r -> "IGNORED".equals(text(r, "status"))));
    cases.put("possible-duplicate", any(rows, r -> "POSSIBLE_DUPLICATE".equals(text(r, "status"))));
    cases.put("soft-deleted", any(rows, r -> r.get("deleted").asBoolean()));
    cases.put("uncategorised", any(rows, r -> text(r, "categoryKey") == null && isSpend(r)));
    cases.put(
        "child-category",
        any(
            rows,
            r -> {
              String c = text(r, "categoryKey");
              return c != null && c.contains(".") && isSpend(r);
            }));
    cases.put(
        "refund-in-expense-category",
        any(
            rows,
            r -> {
              String c = text(r, "categoryKey");
              return "REFUND".equals(text(r, "type"))
                  && c != null
                  && any(
                      rows,
                      o -> "EXPENSE".equals(text(o, "type")) && c.equals(text(o, "categoryKey")));
            }));
    cases.put("merchant-case-and-space-variants", merchantVariants(rows));
    cases.put("merchant-net-not-positive", merchantNetNotPositive(rows));
    cases.put(
        "merchant-blank-or-missing",
        any(
            rows,
            r ->
                isSpend(r)
                    && (text(r, "merchant") == null
                        || String.valueOf(text(r, "merchant")).isBlank())));
    for (String instant : DST_INSTANTS) {
      String name =
          (instant.startsWith("2026-04") ? "dst-april-" : "dst-october-")
              + (instant.endsWith(":59Z") ? "before" : "after");
      cases.put(name, any(rows, r -> instant.equals(text(r, "occurredAt"))));
    }
    for (Map.Entry<String, Boolean> c : cases.entrySet()) {
      if (!c.getValue()) {
        throw new IllegalStateException("seed is missing the case '" + c.getKey() + "'");
      }
    }
  }

  private static boolean any(List<JsonNode> rows, Predicate<JsonNode> p) {
    return rows.stream().anyMatch(p);
  }

  private static boolean isLiveEgp(JsonNode r) {
    return !r.get("deleted").asBoolean()
        && "EGP".equals(text(r, "currency"))
        && ("CONFIRMED".equals(text(r, "status")) || "PENDING_REVIEW".equals(text(r, "status")));
  }

  private static boolean isSpend(JsonNode r) {
    return isLiveEgp(r) && ("EXPENSE".equals(text(r, "type")) || "REFUND".equals(text(r, "type")));
  }

  private static String merchantKey(JsonNode r) {
    String m = text(r, "merchant");
    return m == null ? "" : m.strip().toLowerCase(Locale.ROOT);
  }

  private static boolean merchantVariants(List<JsonNode> rows) {
    return any(
        rows,
        a ->
            any(
                rows,
                b -> {
                  String x = text(a, "merchant");
                  String y = text(b, "merchant");
                  return x != null
                      && y != null
                      && !x.equals(y)
                      && !merchantKey(a).isEmpty()
                      && merchantKey(a).equals(merchantKey(b))
                      && (!x.equals(x.strip()) || !y.equals(y.strip()));
                }));
  }

  private static boolean merchantNetNotPositive(List<JsonNode> rows) {
    Map<String, Long> net = new HashMap<>();
    for (JsonNode r : rows) {
      if (isSpend(r) && !merchantKey(r).isEmpty()) {
        long sign = "REFUND".equals(text(r, "type")) ? -1 : 1;
        String month = String.valueOf(text(r, "occurredAt")).substring(0, 7);
        net.merge(merchantKey(r) + "|" + month, sign * r.get("amountMinor").asLong(), Long::sum);
      }
    }
    return net.values().stream().anyMatch(v -> v <= 0);
  }

  /** Month start days 1 and 25, at least three todays each, covering first, middle and last day. */
  static void checkCases(JsonNode root) {
    Set<Integer> days = new LinkedHashSet<>();
    for (JsonNode c : root.get("cases")) {
      int startDay = c.get("monthStartDay").asInt();
      days.add(startDay);
      List<LocalDate> todays = new ArrayList<>();
      c.get("todays").forEach(t -> todays.add(LocalDate.parse(t.stringValue())));
      require(todays.size() >= 3, "month start day " + startDay + " needs at least three todays");
      boolean first = false;
      boolean middle = false;
      boolean last = false;
      for (LocalDate t : todays) {
        AnalyticsPeriod p = AnalyticsPeriod.currentMonth(t, startDay);
        first |= t.equals(p.from());
        last |= t.equals(p.to());
        middle |= t.isAfter(p.from()) && t.isBefore(p.to());
      }
      require(
          first && middle && last,
          "month start day " + startDay + " needs a first, a middle and a last day of a period");
    }
    require(days.containsAll(Set.of(1, 25)), "cases must cover month start days 1 and 25");
  }

  // ---------------------------------------------------------------- database

  private void assertCatalogMatchesDatabase(JsonNode root) {
    for (JsonNode c : root.get("categories")) {
      String key = text(c, "key");
      Map<String, Object> db =
          jdbc.queryForMap(
              "select c.name_en, c.name_ar, c.color, p.reference_key as parent_key from category c"
                  + " left join category p on p.id = c.parent_id"
                  + " where c.household_id = ? and c.reference_key = ?",
              household,
              key);
      assertThat(db.get("name_en")).as("%s nameEn", key).isEqualTo(text(c, "nameEn"));
      assertThat(db.get("name_ar")).as("%s nameAr", key).isEqualTo(text(c, "nameAr"));
      assertThat(db.get("color")).as("%s color", key).isEqualTo(text(c, "color"));
      assertThat(db.get("parent_key")).as("%s parentKey", key).isEqualTo(text(c, "parentKey"));
    }
  }

  private void loadSeed(JsonNode root) {
    jdbc.update("delete from expense where household_id = ?", household);
    for (JsonNode row : root.get("expenses")) {
      UUID id = UUID.randomUUID();
      String category = text(row, "categoryKey");
      jdbc.update(
          "insert into expense (id, household_id, type, amount_minor, currency, occurred_at,"
              + " category_id, merchant_raw, status, deleted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          id,
          household,
          text(row, "type"),
          row.get("amountMinor").asLong(),
          text(row, "currency"),
          Timestamp.from(Instant.parse(text(row, "occurredAt"))),
          category == null ? null : categoryIds.get(category),
          text(row, "merchant"),
          text(row, "status"),
          row.get("deleted").asBoolean()
              ? Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
              : null);
    }
  }

  // ---------------------------------------------------------------- compute

  private ArrayNode compute(JsonNode root) {
    ArrayNode scenarios = JSON.createArrayNode();
    for (JsonNode c : root.get("cases")) {
      int startDay = c.get("monthStartDay").asInt();
      jdbc.update("update household set month_start_day = ? where id = ?", startDay, household);
      for (JsonNode t : c.get("todays")) {
        LocalDate today = LocalDate.parse(t.stringValue());
        clock.setNow(today.atTime(12, 0).atZone(CAIRO).toInstant());
        ObjectNode scenario = JSON.createObjectNode();
        scenario.put("monthStartDay", startDay);
        scenario.put("today", today.toString());
        scenario.set("outputs", JSON.valueToTree(outputs()));
        scenarios.add(scenario);
      }
    }
    return scenarios;
  }

  private Map<String, Object> outputs() {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("summary", summary(false));
    o.put("summaryWithCash", summary(true));
    var forecast = analytics.forecast("current");
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("period", period(forecast.period()));
    f.put("spentSoFar", minor(forecast.spentSoFar()));
    f.put("linear", minor(forecast.linear()));
    f.put("weekdayAware", minor(forecast.weekdayAware()));
    o.put("forecast", f);
    o.put("byCategory", byCategory(false));
    o.put("byCategoryWithCash", byCategory(true));
    o.put("merchants", merchants());
    o.put("heatmap", heatmap(false));
    o.put("heatmapWithCash", heatmap(true));
    o.put("trend6", trend(6, false));
    o.put("trend6WithCash", trend(6, true));
    o.put("trend12", trend(12, false));
    o.put("trend12WithCash", trend(12, true));
    return o;
  }

  private Map<String, Object> summary(boolean cash) {
    var s = analytics.summary("current", cash);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("period", period(s.period()));
    m.put("total", minor(s.total()));
    m.put("previousTotal", minor(s.previousTotal()));
    m.put("deltaBps", opt(s.deltaBps()));
    m.put("dailyAverage", minor(s.dailyAverage()));
    m.put("projectedTotal", minor(s.projectedTotal()));
    m.put("projectedWeekdayAware", minor(s.projectedWeekdayAware()));
    m.put("income", minor(s.income()));
    m.put("topCategories", lines(s.topCategories()));
    m.put("daysLeft", s.daysLeft());
    m.put("pendingReviewCount", s.pendingReviewCount());
    return m;
  }

  private Map<String, Object> byCategory(boolean cash) {
    var r = analytics.byCategory("current", cash, null);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("period", period(r.period()));
    m.put("total", minor(r.total()));
    m.put("items", lines(r.items()));
    return m;
  }

  private Map<String, Object> merchants() {
    var r = analytics.merchants("current", MERCHANT_LIMIT);
    List<Map<String, Object>> items = new ArrayList<>();
    for (var l : r.items()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", l.name());
      m.put("total", minor(l.total()));
      m.put("visits", l.visits());
      m.put("averageTicket", minor(l.averageTicket()));
      m.put("categoryKey", l.categoryId() == null ? null : categoryKeys.get(l.categoryId()));
      items.add(m);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("period", period(r.period()));
    m.put("items", items);
    return m;
  }

  private Map<String, Object> heatmap(boolean cash) {
    var h = analytics.heatmap("current", cash);
    List<Map<String, Object>> days = new ArrayList<>();
    for (var d : h.days()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("date", d.date().toString());
      m.put("total", minor(d.total()));
      m.put("count", d.count());
      days.add(m);
    }
    List<List<Long>> matrix = new ArrayList<>();
    for (List<Money> row : h.weekdayHour()) {
      matrix.add(row.stream().map(AnalyticsParityIT::minor).toList());
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("period", period(h.period()));
    m.put("days", days);
    m.put("weekdayHour", matrix);
    return m;
  }

  private List<Map<String, Object>> trend(int months, boolean cash) {
    List<Map<String, Object>> points = new ArrayList<>();
    for (var p : analytics.trend(months, cash)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("period", period(p.period()));
      m.put("total", minor(p.total()));
      m.put("income", minor(p.income()));
      m.put("savingsRateBps", opt(p.savingsRateBps()));
      m.put("byCategory", lines(p.byCategory()));
      points.add(m);
    }
    return points;
  }

  private List<Map<String, Object>> lines(List<CategoryLine> lines) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (CategoryLine l : lines) {
      CategoryRef c = l.category();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("categoryKey", c.id() == null ? null : categoryKeys.get(c.id()));
      m.put("nameEn", c.nameEn());
      m.put("nameAr", c.nameAr());
      m.put("color", c.color());
      m.put("total", minor(l.total()));
      m.put("previousTotal", minor(l.previousTotal()));
      m.put("shareBps", l.shareBps());
      m.put("count", l.count());
      m.put("deltaBps", opt(l.deltaBps()));
      out.add(m);
    }
    return out;
  }

  private static Map<String, Object> period(AnalyticsPeriod p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", p.label());
    m.put("from", p.from().toString());
    m.put("to", p.to().toString());
    return m;
  }

  private static long minor(Money money) {
    assertThat(money.currency()).isEqualTo("EGP");
    return money.amountMinor();
  }

  private static @Nullable Integer opt(OptionalInt v) {
    return v.isPresent() ? v.getAsInt() : null;
  }

  // ---------------------------------------------------------------- comparison and rendering

  /** Compares scenario by scenario, output by output; every difference names output and field. */
  static List<String> compare(JsonNode expected, JsonNode actual) {
    List<String> problems = new ArrayList<>();
    if (expected.size() != actual.size()) {
      problems.add("scenarios: expected " + expected.size() + " but computed " + actual.size());
      return problems;
    }
    for (int i = 0; i < expected.size(); i++) {
      JsonNode e = expected.get(i);
      JsonNode a = actual.get(i);
      String scenario = "monthStartDay=" + e.get("monthStartDay") + " today=" + e.get("today");
      if (!e.get("monthStartDay").equals(a.get("monthStartDay"))
          || !e.get("today").equals(a.get("today"))) {
        problems.add(
            "scenario "
                + i
                + " is "
                + scenario
                + " in the file but "
                + a.get("today")
                + " computed");
        continue;
      }
      JsonNode eo = e.get("outputs");
      JsonNode ao = a.get("outputs");
      Set<String> names = new LinkedHashSet<>(eo.propertyNames());
      names.addAll(ao.propertyNames());
      for (String name : names) {
        List<String> fields = new ArrayList<>();
        diff("", eo.get(name), ao.get(name), fields);
        for (String f : fields.stream().limit(3).toList()) {
          problems.add(
              "output '"
                  + name
                  + "' field '"
                  + f.replaceFirst(": ", "': ")
                  + " ("
                  + scenario
                  + ")");
        }
      }
    }
    return problems;
  }

  private static void diff(
      String path, @Nullable JsonNode exp, @Nullable JsonNode act, List<String> out) {
    if (exp == null || act == null) {
      out.add(
          path + ": " + (exp == null ? "missing in the file" : "missing in the computed output"));
    } else if (exp.isObject() && act.isObject()) {
      Set<String> names = new LinkedHashSet<>(exp.propertyNames());
      names.addAll(act.propertyNames());
      for (String n : names) {
        diff(path.isEmpty() ? n : path + "." + n, exp.get(n), act.get(n), out);
      }
    } else if (exp.isArray() && act.isArray()) {
      if (exp.size() != act.size()) {
        out.add(path + ": expected " + exp.size() + " elements but computed " + act.size());
      }
      for (int i = 0; i < Math.min(exp.size(), act.size()); i++) {
        diff(path + "[" + i + "]", exp.get(i), act.get(i), out);
      }
    } else if (!kind(exp).equals(kind(act)) || !exp.toString().equals(act.toString())) {
      out.add(path + ": expected " + exp + " but computed " + act);
    }
  }

  private static String kind(JsonNode n) {
    if (n.isIntegralNumber()) {
      return "integer";
    }
    if (n.isNumber()) {
      return "decimal";
    }
    return n.getNodeType().name();
  }

  /** Containers whose children are all scalars go on one line; the rest is indented. */
  static String render(JsonNode root) {
    StringBuilder sb = new StringBuilder();
    render(root, 0, sb);
    return sb.toString();
  }

  private static void render(JsonNode n, int depth, StringBuilder sb) {
    boolean flat = !n.isContainer() || n.values().stream().noneMatch(JsonNode::isContainer);
    if (flat) {
      sb.append(JSON.writeValueAsString(n));
      return;
    }
    String pad = "  ".repeat(depth + 1);
    boolean object = n.isObject();
    sb.append(object ? "{\n" : "[\n");
    boolean firstItem = true;
    if (object) {
      for (var entry : n.properties()) {
        sb.append(firstItem ? "" : ",\n")
            .append(pad)
            .append(JSON.writeValueAsString(entry.getKey()))
            .append(": ");
        render(entry.getValue(), depth + 1, sb);
        firstItem = false;
      }
    } else {
      for (JsonNode child : n) {
        sb.append(firstItem ? "" : ",\n").append(pad);
        render(child, depth + 1, sb);
        firstItem = false;
      }
    }
    sb.append('\n').append("  ".repeat(depth)).append(object ? '}' : ']');
  }
}
