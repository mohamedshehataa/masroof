package eg.masroof.adapters.out.persistence;

import eg.masroof.application.expense.ReferenceData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link ReferenceData}; every query is scoped by household_id. */
@Repository
class JdbcReferenceData implements ReferenceData {

  private static final String CATEGORY_COLUMNS =
      "id, household_id, name_en, name_ar, icon, color, parent_id, is_essential, sort_order, archived";

  private static final RowMapper<CategoryView> CATEGORY =
      (rs, n) ->
          new CategoryView(
              rs.getObject("id", UUID.class),
              rs.getObject("household_id", UUID.class),
              rs.getString("name_en"),
              rs.getString("name_ar"),
              rs.getString("icon"),
              rs.getString("color"),
              rs.getObject("parent_id", UUID.class),
              rs.getBoolean("is_essential"),
              rs.getInt("sort_order"),
              rs.getBoolean("archived"));

  private static final RowMapper<AccountView> ACCOUNT = JdbcReferenceData::account;

  private final JdbcTemplate jdbc;

  JdbcReferenceData(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static AccountView account(ResultSet rs, int n) throws SQLException {
    return new AccountView(
        rs.getObject("id", UUID.class),
        rs.getObject("member_id", UUID.class),
        rs.getString("type"),
        rs.getString("institution"),
        rs.getString("last4"),
        rs.getString("nickname"),
        rs.getString("currency"),
        rs.getBoolean("is_shared"));
  }

  @Override
  public Optional<CategoryView> category(UUID householdId, UUID categoryId) {
    return jdbc
        .query(
            "select " + CATEGORY_COLUMNS + " from category where household_id = ? and id = ?",
            CATEGORY,
            householdId,
            categoryId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean accountExists(UUID householdId, UUID accountId) {
    return exists("payment_account", householdId, accountId);
  }

  @Override
  public boolean memberExists(UUID householdId, UUID memberId) {
    return exists("member", householdId, memberId);
  }

  private boolean exists(String table, UUID householdId, UUID id) {
    // table is one of two literals above, never user input
    Boolean found =
        jdbc.queryForObject(
            "select exists (select 1 from " + table + " where household_id = ? and id = ?)",
            Boolean.class,
            householdId,
            id);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public List<CategoryView> categories(UUID householdId, boolean includeArchived) {
    return jdbc.query(
        "select "
            + CATEGORY_COLUMNS
            + " from category where household_id = ? and (? or not archived)"
            + " order by sort_order, name_en, id",
        CATEGORY,
        householdId,
        includeArchived);
  }

  @Override
  public List<AccountView> accounts(UUID householdId) {
    return jdbc.query(
        "select id, member_id, type, institution, last4, nickname, currency, is_shared"
            + " from payment_account where household_id = ? order by created_at, id",
        ACCOUNT,
        householdId);
  }
}
