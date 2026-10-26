package eg.masroof.adapters.out.persistence;

import eg.masroof.application.budget.BudgetRepository;
import eg.masroof.domain.budget.BudgetPeriodKind;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC storage for budgets (REQ-AN-008). Literal, household-scoped, parameterised SQL only. */
@Repository
class JdbcBudgetRepository implements BudgetRepository {

  private static final String LIST =
      """
      select id, household_id, category_id, period, amount_minor, currency, rollover,
             alert_thresholds, created_at
      from budget where household_id = ? order by created_at, id
      """;

  private static final String FIND =
      """
      select id, household_id, category_id, period, amount_minor, currency, rollover,
             alert_thresholds, created_at
      from budget where household_id = ? and id = ?
      """;

  private static final String INSERT =
      """
      insert into budget (id, household_id, category_id, period, amount_minor, currency, rollover,
                          alert_thresholds, created_at)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict do nothing
      """;

  private static final String UPDATE =
      """
      update budget set amount_minor = ?, rollover = ?, alert_thresholds = ?
      where household_id = ? and id = ?
      """;

  private static final String DELETE = "delete from budget where household_id = ? and id = ?";

  private static final String CATEGORY =
      "select count(*) from category where household_id = ? and id = ? and not archived";

  private final JdbcTemplate jdbc;

  JdbcBudgetRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Budget> list(UUID householdId) {
    return jdbc.query(LIST, (rs, n) -> map(rs), householdId);
  }

  @Override
  public Optional<Budget> find(UUID householdId, UUID id) {
    return jdbc.query(FIND, (rs, n) -> map(rs), householdId, id).stream().findFirst();
  }

  @Override
  public boolean insert(Budget b) {
    return jdbc.update(
            INSERT,
            ps -> {
              ps.setObject(1, b.id());
              ps.setObject(2, b.householdId());
              ps.setObject(3, b.categoryId());
              ps.setString(4, b.period().name());
              ps.setLong(5, b.amountMinor());
              ps.setString(6, b.currency());
              ps.setBoolean(7, b.rollover());
              ps.setArray(
                  8, ps.getConnection().createArrayOf("integer", b.alertThresholds().toArray()));
              ps.setObject(
                  9, java.time.OffsetDateTime.ofInstant(b.createdAt(), java.time.ZoneOffset.UTC));
            })
        == 1;
  }

  @Override
  public boolean update(Budget b) {
    return jdbc.update(
            UPDATE,
            ps -> {
              ps.setLong(1, b.amountMinor());
              ps.setBoolean(2, b.rollover());
              ps.setArray(
                  3, ps.getConnection().createArrayOf("integer", b.alertThresholds().toArray()));
              ps.setObject(4, b.householdId());
              ps.setObject(5, b.id());
            })
        == 1;
  }

  @Override
  public boolean delete(UUID householdId, UUID id) {
    return jdbc.update(DELETE, householdId, id) == 1;
  }

  @Override
  public boolean categoryUsable(UUID householdId, UUID categoryId) {
    Integer n = jdbc.queryForObject(CATEGORY, Integer.class, householdId, categoryId);
    return n != null && n > 0;
  }

  private static Budget map(ResultSet rs) throws SQLException {
    Array thresholds = rs.getArray("alert_thresholds");
    List<Integer> list =
        thresholds == null ? List.of() : Arrays.asList((Integer[]) thresholds.getArray());
    return new Budget(
        Objects.requireNonNull(rs.getObject("id", UUID.class)),
        Objects.requireNonNull(rs.getObject("household_id", UUID.class)),
        rs.getObject("category_id", UUID.class),
        BudgetPeriodKind.valueOf(rs.getString("period")),
        rs.getLong("amount_minor"),
        Objects.requireNonNull(rs.getString("currency")),
        rs.getBoolean("rollover"),
        list,
        Objects.requireNonNull(rs.getObject("created_at", java.time.OffsetDateTime.class))
            .toInstant());
  }
}
