package eg.masroof.adapters.out.persistence;

import eg.masroof.application.expense.ExpenseItemRepository;
import eg.masroof.domain.expense.ExpenseItem;
import eg.masroof.domain.money.Money;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link ExpenseItemRepository}; all statements are household-scoped. */
@Repository
class JdbcExpenseItemRepository implements ExpenseItemRepository {

  private static final String DELETE =
      "delete from expense_item where household_id = ? and expense_id = ?";

  private static final String INSERT =
      """
      insert into expense_item (id, household_id, expense_id, position, name, qty, total_minor,
        currency)
      values (?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String SELECT =
      """
      select id, expense_id, name, qty, total_minor, currency from expense_item
      where household_id = ? and expense_id = any(?)
      order by expense_id, position
      """;

  private final JdbcTemplate jdbc;

  JdbcExpenseItemRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void replace(UUID householdId, UUID expenseId, List<ExpenseItem> items) {
    jdbc.update(DELETE, householdId, expenseId);
    if (items.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        INSERT,
        items,
        items.size(),
        (ps, item) -> {
          int position = items.indexOf(item);
          ps.setObject(1, item.id());
          ps.setObject(2, householdId);
          ps.setObject(3, expenseId);
          ps.setInt(4, position);
          ps.setString(5, item.name());
          ps.setBigDecimal(6, item.qty());
          if (item.total() == null) {
            ps.setNull(7, Types.BIGINT);
            ps.setNull(8, Types.CHAR);
          } else {
            ps.setLong(7, item.total().amountMinor());
            ps.setString(8, item.total().currency());
          }
        });
  }

  @Override
  public Map<UUID, List<ExpenseItem>> forExpenses(UUID householdId, Collection<UUID> expenseIds) {
    if (expenseIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<ExpenseItem>> byExpense = new HashMap<>();
    jdbc.query(
        con -> {
          var ps = con.prepareStatement(SELECT);
          ps.setObject(1, householdId);
          ps.setArray(2, con.createArrayOf("uuid", expenseIds.toArray()));
          return ps;
        },
        rs -> {
          Long minor = rs.getObject("total_minor", Long.class);
          BigDecimal qty = rs.getBigDecimal("qty");
          String currency = rs.getString("currency");
          Money total = minor == null || currency == null ? null : Money.of(minor, currency);
          byExpense
              .computeIfAbsent(rs.getObject("expense_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new ExpenseItem(
                      rs.getObject("id", UUID.class), rs.getString("name"), qty, total));
        });
    return byExpense;
  }
}
