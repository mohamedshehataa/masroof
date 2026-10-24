package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** REQ-OPS-024: the application context migrates an empty Postgres 16 to exactly V1. */
@SpringBootTest
@Testcontainers
@Tag("integration")
@Tag("migration")
class V1BaselineMigrationTest {

  // postgres:16.15-alpine3.24, pinned by digest (Testcontainers 2 rejects the tag@digest form).
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private final JdbcTemplate jdbc;

  V1BaselineMigrationTest(@Autowired JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 Flyway applies exactly one migration: V1__baseline.sql, successfully")
  void flywayAppliedExactlyV1() {
    assertThat(tableExists("flyway_schema_history"))
        .as("flyway_schema_history exists after context start")
        .isTrue();

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select version, success, script from flyway_schema_history order by installed_rank");

    assertThat(rows)
        .extracting(r -> r.get("version"), r -> r.get("success"), r -> r.get("script"))
        .containsExactly(tuple("1", true, "V1__baseline.sql"));
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 household has the spec columns, types, NOT NULL everywhere, id as PK")
  void householdColumnsMatchSpec() {
    assertHouseholdExists();

    List<Map<String, Object>> columns =
        jdbc.queryForList(
            "select column_name, data_type, character_maximum_length, is_nullable"
                + " from information_schema.columns"
                + " where table_schema = current_schema() and table_name = 'household'"
                + " order by ordinal_position");

    assertThat(columns)
        .extracting(
            c -> c.get("column_name"),
            c -> c.get("data_type"),
            c -> c.get("character_maximum_length"),
            c -> c.get("is_nullable"))
        .containsExactly(
            tuple("id", "uuid", null, "NO"),
            tuple("name", "text", null, "NO"),
            tuple("base_currency", "character", 3, "NO"),
            tuple("timezone", "text", null, "NO"),
            tuple("month_start_day", "integer", null, "NO"),
            tuple("created_at", "timestamp with time zone", null, "NO"),
            tuple("updated_at", "timestamp with time zone", null, "NO"));

    List<String> primaryKey =
        jdbc.queryForList(
            "select k.column_name from information_schema.table_constraints t"
                + " join information_schema.key_column_usage k"
                + "   on k.constraint_name = t.constraint_name and k.table_schema = t.table_schema"
                + " where t.table_schema = current_schema() and t.table_name = 'household'"
                + "   and t.constraint_type = 'PRIMARY KEY'",
            String.class);
    assertThat(primaryKey).containsExactly("id");
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 insert with only id and name gets EGP, Africa/Cairo, day 1, timestamps")
  void insertWithOnlyIdAndNameGetsDefaults() {
    assertHouseholdExists();
    UUID id = UUID.randomUUID();

    jdbc.update("insert into household (id, name) values (?, ?)", id, "Defaults household");

    Map<String, Object> row =
        jdbc.queryForMap(
            "select base_currency, timezone, month_start_day, created_at, updated_at"
                + " from household where id = ?",
            id);
    assertThat(row)
        .containsEntry("base_currency", "EGP")
        .containsEntry("timezone", "Africa/Cairo")
        .containsEntry("month_start_day", 1);
    assertThat(row.get("created_at")).as("created_at").isNotNull();
    assertThat(row.get("updated_at")).as("updated_at").isNotNull();
  }

  @ParameterizedTest(name = "REQ-OPS-024 month_start_day = {0} is rejected with SQLSTATE 23514")
  @ValueSource(ints = {0, 29})
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 CHECK rejects month_start_day outside 1..28 (check violation 23514)")
  void monthStartDayOutsideRangeIsRejected(int day) {
    assertHouseholdExists();

    assertThatThrownBy(() -> insertWithMonthStartDay(day))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            e ->
                assertThat(((DataIntegrityViolationException) e).getMostSpecificCause())
                    .isInstanceOfSatisfying(
                        SQLException.class,
                        sql -> assertThat(sql.getSQLState()).isEqualTo("23514")));
  }

  @ParameterizedTest(name = "REQ-OPS-024 month_start_day = {0} is accepted")
  @ValueSource(ints = {1, 28})
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 CHECK accepts the bounds month_start_day 1 and 28")
  void monthStartDayBoundsAreAccepted(int day) {
    assertHouseholdExists();

    UUID id = insertWithMonthStartDay(day);

    assertThat(
            jdbc.queryForList(
                "select month_start_day from household where id = ?", Integer.class, id))
        .containsExactly(day);
  }

  private UUID insertWithMonthStartDay(int day) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into household (id, name, month_start_day) values (?, ?, ?)",
        id,
        "Month start household",
        day);
    return id;
  }

  private void assertHouseholdExists() {
    assertThat(tableExists("household")).as("table household exists (V1 applied)").isTrue();
  }

  private boolean tableExists(String table) {
    List<Integer> found =
        jdbc.queryForList(
            "select 1 from information_schema.tables"
                + " where table_schema = current_schema() and table_name = ?",
            Integer.class,
            table);
    return !found.isEmpty();
  }
}
