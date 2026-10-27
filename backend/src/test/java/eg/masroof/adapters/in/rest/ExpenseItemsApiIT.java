package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Purchased items on an expense against real Postgres (STORY-168, REQ-RCPT-030..032). */
@SpringBootTest(properties = "masroof.dev.single-household=true")
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseItemsApiIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeAll
  void seed() {
    jdbc.update(
        "insert into household (id, name, created_at) values (gen_random_uuid(), 'Items', now() - interval '1 day')");
  }

  private static Map<String, Object> item(String name, @Nullable String qty, @Nullable Long minor) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("name", name);
    if (qty != null) {
      m.put("qty", qty);
    }
    if (minor != null) {
      m.put("total", Map.of("amountMinor", minor, "currency", "EGP"));
    }
    return m;
  }

  private static Map<String, Object> expense(@Nullable List<Map<String, Object>> items) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("type", "EXPENSE");
    m.put("amount", Map.of("amountMinor", 10_000, "currency", "EGP"));
    m.put("occurredAt", "2026-10-20T10:00:00Z");
    if (items != null) {
      m.put("items", items);
    }
    return m;
  }

  private MockHttpServletResponse create(Map<String, Object> body) throws Exception {
    return mvc.perform(
            post("/api/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse patchItems(String id, Map<String, Object> body) throws Exception {
    return mvc.perform(
            patch("/api/expenses/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private JsonNode read(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString());
  }

  private JsonNode fetch(String id) throws Exception {
    MockHttpServletResponse r = mvc.perform(get("/api/expenses/" + id)).andReturn().getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    return read(r);
  }

  private static List<String> names(JsonNode expense) {
    List<String> out = new ArrayList<>();
    expense.get("items").forEach(i -> out.add(i.get("name").asString()));
    return out;
  }

  @Test
  @Tag("REQ-RCPT-030")
  @Tag("REQ-RCPT-032")
  @DisplayName("REQ-RCPT-030 items are stored in order and returned by create, get and list")
  void storedAndReturned() throws Exception {
    MockHttpServletResponse r =
        create(
            expense(
                List.of(
                    item("  Bread ", null, null),
                    item("Milk", "2", 3_500L),
                    item("Cheese", "1.5", 0L))));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    JsonNode created = read(r);
    assertThat(names(created)).containsExactly("Bread", "Milk", "Cheese");
    JsonNode milk = created.get("items").get(1);
    assertThat(milk.get("id").asString()).isNotBlank();
    assertThat(milk.get("qty").asString()).isEqualTo("2");
    assertThat(milk.get("total").get("amountMinor").asLong()).isEqualTo(3_500);
    assertThat(milk.get("total").get("currency").asString()).isEqualTo("EGP");
    assertThat(created.get("items").get(0).has("total")).isFalse();
    assertThat(created.get("items").get(2).get("qty").asString()).isEqualTo("1.5");
    assertThat(created.get("items").get(2).get("total").get("amountMinor").asLong()).isZero();

    String id = created.get("id").asString();
    assertThat(names(fetch(id))).containsExactly("Bread", "Milk", "Cheese");
    JsonNode page = read(mvc.perform(get("/api/expenses?limit=100")).andReturn().getResponse());
    JsonNode listed = null;
    for (JsonNode e : page.get("items")) {
      if (e.get("id").asString().equals(id)) {
        listed = e;
      }
    }
    assertThat(Objects.requireNonNull(listed).get("items")).hasSize(3);
    // stored as integral minor units + currency, quantity as NUMERIC(12,3)
    Map<String, Object> row =
        jdbc.queryForMap(
            "select total_minor, currency, qty, position from expense_item"
                + " where expense_id = ?::uuid and name = 'Milk'",
            id);
    assertThat(row.get("total_minor")).isEqualTo(3_500L);
    assertThat(row.get("currency")).isEqualTo("EGP");
    assertThat(String.valueOf(row.get("qty"))).isEqualTo("2.000");
    assertThat(row.get("position")).isEqualTo(1);
  }

  @Test
  @Tag("REQ-RCPT-030")
  @DisplayName("REQ-RCPT-030 PATCH without items keeps them; a list replaces them; [] clears them")
  void patchSemantics() throws Exception {
    String id =
        read(create(expense(List.of(item("A", null, null), item("B", "1", 100L)))))
            .get("id")
            .asString();
    MockHttpServletResponse plain = patchItems(id, Map.of("notes", "hello"));
    assertThat(plain.getStatus()).isEqualTo(200);
    assertThat(names(read(plain))).containsExactly("A", "B");

    MockHttpServletResponse replaced =
        patchItems(
            id,
            Map.of(
                "items",
                List.of(item("C", null, 5L), item("D", "0.25", null), item("E", null, null))));
    assertThat(replaced.getStatus()).as(replaced.getContentAsString()).isEqualTo(200);
    assertThat(names(read(replaced))).containsExactly("C", "D", "E");
    assertThat(names(fetch(id))).containsExactly("C", "D", "E");
    assertThat(
            Objects.requireNonNull(
                jdbc.queryForObject(
                    "select count(*) from expense_item where expense_id = ?::uuid",
                    Integer.class,
                    id)))
        .isEqualTo(3);

    MockHttpServletResponse cleared = patchItems(id, Map.of("items", List.of()));
    assertThat(cleared.getStatus()).isEqualTo(200);
    assertThat(read(cleared).get("items")).isEmpty();
    assertThat(fetch(id).get("items")).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-031")
  @DisplayName("REQ-RCPT-031 invalid items are 400 naming the field and change nothing")
  void validation() throws Exception {
    String id = read(create(expense(List.of(item("Keep", null, null))))).get("id").asString();

    MockHttpServletResponse badTotal =
        patchItems(
            id,
            Map.of(
                "items",
                List.of(item("a", null, null), item("b", null, null), item("c", null, -1L))));
    assertThat(badTotal.getStatus()).isEqualTo(400);
    assertThat(read(badTotal).get("errors").get(0).get("field").asString())
        .isEqualTo("items[2].total.amountMinor");
    assertThat(names(fetch(id))).containsExactly("Keep");

    assertThat(errorFields(patchItems(id, Map.of("items", List.of(item("  ", null, null))))))
        .containsExactly("items[0].name");
    assertThat(errorFields(patchItems(id, Map.of("items", List.of(item("a", "0", null))))))
        .containsExactly("items[0].qty");
    assertThat(errorFields(patchItems(id, Map.of("items", List.of(item("a", "1.2345", null))))))
        .containsExactly("items[0].qty");
    Map<String, Object> usd = item("a", null, null);
    usd.put("total", Map.of("amountMinor", 5, "currency", "USD"));
    assertThat(errorFields(patchItems(id, Map.of("items", List.of(usd)))))
        .containsExactly("items[0].total.currency");
    Map<String, Object> withUnitPrice = item("a", null, null);
    withUnitPrice.put("unitPrice", Map.of("amountMinor", 5, "currency", "EGP"));
    assertThat(errorFields(patchItems(id, Map.of("items", List.of(withUnitPrice)))))
        .containsExactly("items[0].unitPrice");

    List<Map<String, Object>> many = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      many.add(item("i" + i, null, null));
    }
    assertThat(patchItems(id, Map.of("items", many)).getStatus()).isEqualTo(400);
    MockHttpServletResponse onCreate =
        create(expense(List.of(item("ok", null, null), item("", null, null))));
    assertThat(onCreate.getStatus()).isEqualTo(400);
    assertThat(errorFields(onCreate)).containsExactly("items[1].name");
    assertThat(names(fetch(id))).containsExactly("Keep");
  }

  @Test
  @Tag("REQ-RCPT-031")
  @DisplayName("REQ-RCPT-031 a name of 200 characters and 100 items are accepted")
  void limits() throws Exception {
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      items.add(item(i == 0 ? "x".repeat(200) : "i" + i, null, null));
    }
    MockHttpServletResponse r = create(expense(items));
    assertThat(r.getStatus()).isEqualTo(201);
    assertThat(read(r).get("items")).hasSize(100);
  }

  private List<String> errorFields(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(400);
    List<String> out = new ArrayList<>();
    read(r).get("errors").forEach(e -> out.add(e.get("field").asString()));
    return out;
  }
}
