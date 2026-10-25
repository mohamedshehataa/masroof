package eg.masroof.adapters.out.persistence;

import eg.masroof.application.expense.Cursor;
import eg.masroof.application.expense.ExpenseFilter;
import eg.masroof.application.expense.ExpenseRepository;
import eg.masroof.domain.expense.Expense;
import eg.masroof.domain.expense.ExpenseSource;
import eg.masroof.domain.expense.ExpenseStatus;
import eg.masroof.domain.expense.ExpenseType;
import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link ExpenseRepository}. All queries are parameterised and
 * household-scoped. Each statement is one literal; the only dynamic parts are fragments chosen from
 * fixed lists in this class (filter predicates, a column from the {@code ExpenseSort} whitelist)
 * and every user-supplied value is bound as a parameter.
 */
@Repository
class JdbcExpenseRepository implements ExpenseRepository {

  private static final String INSERT =
      """
      insert into expense (id, household_id, type, amount_minor, currency, occurred_at,
        category_id, payment_account_id, paid_by_member_id, payment_method, merchant_raw,
        note, tags, status, source, created_at, updated_at)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)
      returning *
      """;

  private static final String FIND =
      """
      select * from expense where household_id = ? and id = ?
      """;

  private static final String UPDATE =
      """
      update expense set type = ?, amount_minor = ?, currency = ?, occurred_at = ?,
        category_id = ?, payment_account_id = ?, paid_by_member_id = ?, payment_method = ?,
        merchant_raw = ?, note = ?, tags = ?::text[], status = ?
      where household_id = ? and id = ? and deleted_at is null
      returning *
      """;

  private static final String SOFT_DELETE =
      """
      update expense set deleted_at = ?
      where household_id = ? and id = ? and deleted_at is null
      """;

  private static final String LIST_HEAD = "select * from expense";

  private static final String TOTAL_HEAD = "select coalesce(sum(amount_minor), 0) from expense";

  private static final String FIND_IDEMPOTENCY =
      """
      select fingerprint, expense_id, created_at from idempotency_key
      where household_id = ? and key = ?
      """;

  private static final String CLAIM_IDEMPOTENCY =
      """
      insert into idempotency_key (household_id, key, fingerprint, expense_id, created_at)
      values (?, ?, ?, ?, ?)
      on conflict (household_id, key) do update set fingerprint = excluded.fingerprint,
        expense_id = excluded.expense_id, created_at = excluded.created_at
      where idempotency_key.created_at < ?
      """;

  private static final RowMapper<Expense> MAPPER = JdbcExpenseRepository::map;

  private final JdbcTemplate jdbc;

  JdbcExpenseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static @Nullable Instant nullableInstant(ResultSet rs, String column)
      throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return Objects.requireNonNull(nullableInstant(rs, column), column);
  }

  private static Expense map(ResultSet rs, int rowNum) throws SQLException {
    String[] tags = (String[]) rs.getArray("tags").getArray();
    return new Expense(
        rs.getObject("id", UUID.class),
        rs.getObject("household_id", UUID.class),
        ExpenseType.valueOf(rs.getString("type")),
        Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
        instant(rs, "occurred_at"),
        rs.getObject("category_id", UUID.class),
        rs.getObject("payment_account_id", UUID.class),
        rs.getObject("paid_by_member_id", UUID.class),
        PaymentMethod.valueOf(rs.getString("payment_method")),
        rs.getString("merchant_raw"),
        rs.getString("note"),
        List.of(tags),
        ExpenseStatus.valueOf(rs.getString("status")),
        ExpenseSource.valueOf(rs.getString("source")),
        nullableInstant(rs, "deleted_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  /** Joins fixed statement fragments (all literals or built from bound-parameter predicates). */
  private static String compose(String... fragments) {
    return String.join("", fragments);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  @Override
  public Expense insert(Expense e) {
    return jdbc.queryForObject(
        INSERT,
        MAPPER,
        e.id(),
        e.householdId(),
        e.type().name(),
        e.amount().amountMinor(),
        e.amount().currency(),
        utc(e.occurredAt()),
        e.categoryId(),
        e.paymentAccountId(),
        e.paidByMemberId(),
        e.paymentMethod().name(),
        e.merchantRaw(),
        e.notes(),
        e.tags().toArray(new String[0]),
        e.status().name(),
        e.source().name(),
        utc(e.createdAt()),
        utc(e.updatedAt()));
  }

  @Override
  public Optional<Expense> find(UUID householdId, UUID id) {
    return jdbc.query(FIND, MAPPER, householdId, id).stream().findFirst();
  }

  @Override
  public Expense update(Expense e) {
    return jdbc.queryForObject(
        UPDATE,
        MAPPER,
        e.type().name(),
        e.amount().amountMinor(),
        e.amount().currency(),
        utc(e.occurredAt()),
        e.categoryId(),
        e.paymentAccountId(),
        e.paidByMemberId(),
        e.paymentMethod().name(),
        e.merchantRaw(),
        e.notes(),
        e.tags().toArray(new String[0]),
        e.status().name(),
        e.householdId(),
        e.id());
  }

  @Override
  public boolean softDelete(UUID householdId, UUID id, Instant now) {
    return jdbc.update(SOFT_DELETE, utc(now), householdId, id) > 0;
  }

  /** WHERE clause without the cursor condition; predicates are fixed text, values are bound. */
  private static String where(ExpenseFilter f, List<Object> args) {
    StringBuilder sql = new StringBuilder(" where household_id = ?");
    args.add(f.householdId());
    if (!f.includeDeleted()) {
      sql.append(" and deleted_at is null");
    }
    if (f.fromInclusive() != null) {
      sql.append(" and occurred_at >= ?");
      args.add(utc(f.fromInclusive()));
    }
    if (f.toExclusive() != null) {
      sql.append(" and occurred_at < ?");
      args.add(utc(f.toExclusive()));
    }
    if (f.memberId() != null) {
      sql.append(" and paid_by_member_id = ?");
      args.add(f.memberId());
    }
    if (f.categoryId() != null) {
      sql.append(
          " and (category_id = ? or category_id in (select id from category where parent_id = ?))");
      args.add(f.categoryId());
      args.add(f.categoryId());
    }
    if (f.merchantFilter()) {
      sql.append(" and false"); // no merchant table yet: nothing can match a merchant filter
    }
    if (f.accountId() != null) {
      sql.append(" and payment_account_id = ?");
      args.add(f.accountId());
    }
    if (f.source() != null) {
      sql.append(" and source = ?");
      args.add(f.source().name());
    }
    if (f.status() != null) {
      sql.append(" and status = ?");
      args.add(f.status().name());
    }
    if (f.type() != null) {
      sql.append(" and type = ?");
      args.add(f.type().name());
    }
    if (f.minAmountMinor() != null) {
      sql.append(" and amount_minor >= ?");
      args.add(f.minAmountMinor());
    }
    if (f.maxAmountMinor() != null) {
      sql.append(" and amount_minor <= ?");
      args.add(f.maxAmountMinor());
    }
    if (f.text() != null) {
      String like =
          "%" + f.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      sql.append(" and (merchant_raw ilike ? escape '\\' or note ilike ? escape '\\')");
      args.add(like);
      args.add(like);
    }
    return sql.toString();
  }

  @Override
  public List<Expense> list(ExpenseFilter f) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder();
    sql.append(compose(LIST_HEAD, where(f, args)));
    String column = f.sort().column(); // from the ExpenseSort whitelist, never user input
    String dir = f.sort().descending() ? "desc" : "asc";
    Cursor after = f.after();
    if (after != null) {
      sql.append(" and (").append(column).append(", id) ");
      sql.append(f.sort().descending() ? "<" : ">").append(" (?, ?)");
      args.add(f.sort().isAmount() ? (Object) after.amountMinor() : utc(after.instant()));
      args.add(after.id());
    }
    sql.append(" order by ").append(column).append(' ').append(dir);
    sql.append(", id ").append(dir).append(" limit ?");
    args.add(f.limit() + 1);
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
  }

  @Override
  public long totalMinor(ExpenseFilter f) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder();
    sql.append(compose(TOTAL_HEAD, where(f, args), " and type <> 'TRANSFER'"));
    Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return total == null ? 0 : total;
  }

  @Override
  public Optional<IdempotencyRecord> findIdempotency(UUID householdId, String key) {
    return jdbc
        .query(
            FIND_IDEMPOTENCY,
            (rs, n) ->
                new IdempotencyRecord(
                    rs.getString("fingerprint"),
                    rs.getObject("expense_id", UUID.class),
                    instant(rs, "created_at")),
            householdId,
            key)
        .stream()
        .findFirst();
  }

  @Override
  public boolean claimIdempotency(
      UUID householdId,
      String key,
      String fingerprint,
      UUID expenseId,
      Instant now,
      Instant staleBefore) {
    return jdbc.update(
            CLAIM_IDEMPOTENCY, householdId, key, fingerprint, expenseId, utc(now), utc(staleBefore))
        > 0;
  }
}
