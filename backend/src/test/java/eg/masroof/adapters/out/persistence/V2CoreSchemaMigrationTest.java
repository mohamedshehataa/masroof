package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * STORY-004 migrations: V1 data survives V2/V3, the reference categories are copied into the V1-era
 * household, and the constraints hold.
 */
@Testcontainers
@Tag("integration")
@Tag("migration")
class V2CoreSchemaMigrationTest {

  private static final String BCRYPT =
      "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static JdbcTemplate jdbc;
  private static final UUID OLD_HOUSEHOLD = UUID.randomUUID();

  @BeforeAll
  static void migrateFromV1() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    // "RX__" hides the repeatable seed from this first run: it must run after V2/V3, as in a real
    // upgrade.
    Flyway.configure()
        .dataSource(ds)
        .target("1")
        .repeatableSqlMigrationPrefix("RX__")
        .load()
        .migrate();
    jdbc.update("insert into household (id, name) values (?, 'Old household')", OLD_HOUSEHOLD);
    Flyway.configure().dataSource(ds).load().migrate();
  }

  private static UUID household() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into household (id, name) values (?, 'H')", id);
    return id;
  }

  private static UUID member(UUID household) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into member (id, household_id, display_name) values (?, ?, 'Mohamed')",
        id,
        household);
    return id;
  }

  private static String sqlState(Throwable t) {
    assertThat(t).isInstanceOf(DataIntegrityViolationException.class);
    Throwable cause = ((DataIntegrityViolationException) t).getMostSpecificCause();
    assertThat(cause).isInstanceOf(SQLException.class);
    return ((SQLException) cause).getSQLState();
  }

  @Test
  @Tag("REQ-OPS-040")
  @DisplayName("REQ-OPS-040 V1 is followed by V2 and V3 and the repeatable seed, all successful")
  void migrationsApplied() {
    List<String> scripts =
        jdbc.queryForList(
            "select script from flyway_schema_history where success order by installed_rank",
            String.class);
    assertThat(scripts)
        .containsExactly(
            "V1__baseline.sql",
            "V2__core_schema.sql",
            "V3__expenses.sql",
            "V4__household_currency_check.sql",
            "V5__refresh_token_family.sql",
            "V6__audit_log_ip_hash.sql",
            "V7__analytics_index.sql",
            "R__seed_reference.sql");
  }

  @ParameterizedTest
  @ValueSource(strings = {"egp", "EG ", "E1P", "EGPP", "€€€"})
  @Tag("REQ-OPS-047")
  @DisplayName("REQ-OPS-047 household base_currency must be three upper-case letters")
  void invalidBaseCurrencyRejected(String code) {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into household (id, name, base_currency) values (?, 'H', ?)",
                    UUID.randomUUID(),
                    code))
        .satisfies(e -> assertThat(sqlState(e)).isIn("23514", "22001"));
  }

  @Test
  @Tag("REQ-OPS-047")
  @DisplayName("REQ-OPS-047 a valid base_currency such as USD is accepted")
  void validBaseCurrencyAccepted() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into household (id, name, base_currency) values (?, 'H', 'USD')", id);
    assertThat(
            jdbc.queryForObject(
                "select base_currency from household where id = ?", String.class, id))
        .isEqualTo("USD");
  }

  @Test
  @Tag("REQ-OPS-041")
  @DisplayName("REQ-OPS-041 the household that existed at V1 gets the reference categories")
  void existingHouseholdIsSeeded() {
    Integer top =
        jdbc.queryForObject(
            "select count(*) from category where household_id = ? and parent_id is null",
            Integer.class,
            OLD_HOUSEHOLD);
    Integer sub =
        jdbc.queryForObject(
            "select count(*) from category where household_id = ? and parent_id is not null",
            Integer.class,
            OLD_HOUSEHOLD);
    assertThat(top).isEqualTo(20);
    assertThat(sub).isEqualTo(9);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from category c join category p on p.id = c.parent_id"
                    + " where c.household_id = ? and p.household_id <> c.household_id",
                Integer.class,
                OLD_HOUSEHOLD))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "select name_en from category where household_id = ? and reference_key = 'car.fuel'",
                String.class,
                OLD_HOUSEHOLD))
        .containsExactly("Fuel");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from category where household_id = ?"
                    + " and (name_ar is null or btrim(name_ar) = '')",
                Integer.class,
                OLD_HOUSEHOLD))
        .isZero();
  }

  @Test
  @Tag("REQ-OPS-041")
  @DisplayName("REQ-OPS-041 the seed is idempotent and keeps household edits")
  void seedIsIdempotent() {
    UUID h = household();
    jdbc.queryForList("select seed_household_categories(?)", h);
    jdbc.update(
        "update category set name_en = 'Petrol', archived = true"
            + " where household_id = ? and reference_key = 'car.fuel'",
        h);
    jdbc.queryForList("select seed_household_categories(?)", h);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from category where household_id = ?", Integer.class, h))
        .isEqualTo(29);
    assertThat(
            jdbc.queryForMap(
                "select name_en, archived from category"
                    + " where household_id = ? and reference_key = 'car.fuel'",
                h))
        .containsEntry("name_en", "Petrol")
        .containsEntry("archived", true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  @Tag("REQ-OPS-047")
  @DisplayName("REQ-OPS-047 household name must not be blank")
  void blankHouseholdNameRejected(String name) {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into household (id, name) values (?, ?)", UUID.randomUUID(), name))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
  }

  @Test
  @Tag("REQ-OPS-047")
  @DisplayName("REQ-OPS-047 household timezone must be Africa/Cairo")
  void otherTimezoneRejected() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into household (id, name, timezone) values (?, 'H', 'UTC')",
                    UUID.randomUUID()))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
  }

  @Test
  @Tag("REQ-OPS-047")
  @DisplayName("REQ-OPS-047 updating a household moves updated_at forward")
  void updatedAtTrigger() {
    UUID h = household();
    jdbc.update("update household set updated_at = now() - interval '1 day' where id = ?", h);
    jdbc.update("update household set name = 'Renamed' where id = ?", h);
    Boolean fresh =
        jdbc.queryForObject(
            "select updated_at > now() - interval '1 minute' from household where id = ?",
            Boolean.class,
            h);
    assertThat(fresh).isTrue();
  }

  @Test
  @Tag("REQ-OPS-040")
  @DisplayName("REQ-OPS-040 user emails are unique case-insensitively and need a bcrypt hash")
  void appUserConstraints() {
    UUID h = household();
    UUID m1 = member(h);
    UUID m2 = member(h);
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, 'A@Example.com', ?)",
        UUID.randomUUID(),
        m1,
        BCRYPT);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into app_user (id, member_id, email, password_hash)"
                        + " values (?, ?, 'a@example.COM', ?)",
                    UUID.randomUUID(),
                    m2,
                    BCRYPT))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into app_user (id, member_id, email, password_hash)"
                        + " values (?, ?, 'b@example.com', 'plaintext')",
                    UUID.randomUUID(),
                    m2))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
  }

  @Test
  @Tag("REQ-OPS-040")
  @DisplayName("REQ-OPS-040 refresh tokens store a 64-hex hash, never the token")
  void refreshTokenHashCheck() {
    UUID h = household();
    UUID user = UUID.randomUUID();
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, 'r@example.com', ?)",
        user,
        member(h),
        BCRYPT);
    jdbc.update(
        "insert into refresh_token (id, user_id, token_hash, expires_at)"
            + " values (?, ?, ?, now() + interval '30 days')",
        UUID.randomUUID(),
        user,
        "a".repeat(64));
    for (String bad : List.of("a".repeat(63), "A".repeat(64), "g".repeat(64))) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "insert into refresh_token (id, user_id, token_hash, expires_at)"
                          + " values (?, ?, ?, now())",
                      UUID.randomUUID(),
                      user,
                      bad))
          .satisfies(e -> assertThat(sqlState(e)).isIn("23514", "22001"));
    }
  }

  @Test
  @Tag("REQ-OPS-040")
  @DisplayName("REQ-OPS-040 payment accounts validate type, last4 and currency")
  void paymentAccountChecks() {
    UUID h = household();
    UUID m = member(h);
    String sql =
        "insert into payment_account (id, household_id, member_id, type, last4, currency)"
            + " values (?, ?, ?, ?, ?, ?)";
    jdbc.update(sql, UUID.randomUUID(), h, m, "CARD", "1234", "EGP");
    assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), h, m, "CRYPTO", "1234", "EGP"))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), h, m, "CARD", "12a4", "EGP"))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), h, m, "CARD", "1234", "egp"))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
  }

  @Test
  @Tag("REQ-EXP-002")
  @DisplayName(
      "REQ-EXP-002 expense rows default to MANUAL/CONFIRMED and reject non-positive amounts")
  void expenseChecks() {
    UUID h = household();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into expense (id, household_id, amount_minor, currency, occurred_at)"
            + " values (?, ?, 1250, 'EGP', now())",
        id,
        h);
    assertThat(jdbc.queryForMap("select source, status, type, tags from expense where id = ?", id))
        .containsEntry("source", "MANUAL")
        .containsEntry("status", "CONFIRMED")
        .containsEntry("type", "EXPENSE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into expense (id, household_id, amount_minor, currency, occurred_at)"
                        + " values (?, ?, 0, 'EGP', now())",
                    UUID.randomUUID(),
                    h))
        .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
  }
}
