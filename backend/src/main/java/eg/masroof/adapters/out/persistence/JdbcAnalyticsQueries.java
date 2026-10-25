package eg.masroof.adapters.out.persistence;

import eg.masroof.application.analytics.AnalyticsQueries;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC aggregates for analytics (REQ-AN-001 to -007). Every statement is a literal, household
 * scoped and fully parameterised. Cairo days and hours come from {@code at time zone
 * 'Africa/Cairo'}, so DST shifts are handled by the database tz rules.
 */
@Repository
class JdbcAnalyticsQueries implements AnalyticsQueries {

  private static final String UNCATEGORISED_EN = "Uncategorised";
  private static final String UNCATEGORISED_AR = "غير مصنف";

  // Spend queries below repeat one filter on purpose (SQL stays literal for semgrep): base
  // currency, live rows,
  // CONFIRMED/PENDING_REVIEW, EXPENSE and REFUND (+ CASH_WITHDRAWAL when the flag is bound true);
  // refunds net off.

  private static final String MONTH_START_DAY =
      "select month_start_day from household where id = ?";

  private static final String DAY_HOUR =
      """
      select (e.occurred_at at time zone 'Africa/Cairo')::date as d,
             extract(hour from e.occurred_at at time zone 'Africa/Cairo')::int as h,
             sum(case e.type when 'REFUND' then -e.amount_minor else e.amount_minor end) as total, count(*) as cnt
      from expense e
      where e.household_id = ? and e.deleted_at is null and e.currency = ?
        and e.status in ('CONFIRMED', 'PENDING_REVIEW')
        and (e.type in ('EXPENSE', 'REFUND') or (? and e.type = 'CASH_WITHDRAWAL'))
        and e.occurred_at >= ? and e.occurred_at < ?
      group by 1, 2
      order by 1, 2
      """;

  private static final String CATEGORY_ROLLED =
      """
      select (e.occurred_at at time zone 'Africa/Cairo')::date as d,
             coalesce(p.id, c.id) as cid,
             coalesce(p.name_en, c.name_en, ?::text) as name_en,
             coalesce(p.name_ar, c.name_ar, ?::text) as name_ar,
             coalesce(p.color, c.color) as color,
             sum(case e.type when 'REFUND' then -e.amount_minor else e.amount_minor end) as total, count(*) as cnt
      from expense e
      left join category c on c.id = e.category_id
      left join category p on p.id = c.parent_id
      where e.household_id = ? and e.deleted_at is null and e.currency = ?
        and e.status in ('CONFIRMED', 'PENDING_REVIEW')
        and (e.type in ('EXPENSE', 'REFUND') or (? and e.type = 'CASH_WITHDRAWAL'))
        and e.occurred_at >= ? and e.occurred_at < ?
      group by 1, 2, 3, 4, 5
      """;

  private static final String CATEGORY_DRILL =
      """
      select (e.occurred_at at time zone 'Africa/Cairo')::date as d,
             c.id as cid, c.name_en as name_en, c.name_ar as name_ar, c.color as color,
             sum(case e.type when 'REFUND' then -e.amount_minor else e.amount_minor end) as total, count(*) as cnt
      from expense e
      join category c on c.id = e.category_id
      where e.household_id = ? and e.deleted_at is null and e.currency = ?
        and e.status in ('CONFIRMED', 'PENDING_REVIEW')
        and (e.type in ('EXPENSE', 'REFUND') or (? and e.type = 'CASH_WITHDRAWAL'))
        and e.occurred_at >= ? and e.occurred_at < ? and (c.id = ? or c.parent_id = ?)
      group by 1, 2, 3, 4, 5
      """;

  private static final String INCOME =
      """
      select (e.occurred_at at time zone 'Africa/Cairo')::date as d, sum(e.amount_minor) as total
      from expense e
      where e.household_id = ? and e.deleted_at is null and e.currency = ?
        and e.status in ('CONFIRMED', 'PENDING_REVIEW') and e.type = 'INCOME'
        and e.occurred_at >= ? and e.occurred_at < ?
      group by 1
      order by 1
      """;

  private static final String MERCHANTS =
      """
      select min(btrim(e.merchant_raw)) as name,
             sum(case e.type when 'REFUND' then -e.amount_minor else e.amount_minor end) as total,
             count(*) as visits,
             mode() within group (order by e.category_id) as category_id
      from expense e
      where e.household_id = ? and e.deleted_at is null and e.currency = ?
        and e.status in ('CONFIRMED', 'PENDING_REVIEW')
        and (e.type in ('EXPENSE', 'REFUND') or (? and e.type = 'CASH_WITHDRAWAL'))
        and e.occurred_at >= ? and e.occurred_at < ? and e.merchant_raw is not null and btrim(e.merchant_raw) <> ''
      group by lower(btrim(e.merchant_raw))
      having sum(case e.type when 'REFUND' then -e.amount_minor else e.amount_minor end) > 0
      order by total desc, name
      limit ?
      """;

  private static final String PENDING =
      """
      select count(*) from expense e
      where e.household_id = ? and e.deleted_at is null and e.status = 'PENDING_REVIEW'
        and e.occurred_at >= ? and e.occurred_at < ?
      """;

  private final JdbcTemplate jdbc;

  JdbcAnalyticsQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static OffsetDateTime ts(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  @Override
  public int monthStartDay(UUID householdId) {
    Integer day = jdbc.queryForObject(MONTH_START_DAY, Integer.class, householdId);
    return day == null ? 1 : day;
  }

  @Override
  public List<DayHour> dayHourTotals(
      UUID householdId, String currency, Instant from, Instant to, boolean includeCash) {
    return jdbc.query(
        DAY_HOUR,
        (ResultSet rs, int n) ->
            new DayHour(
                rs.getObject("d", LocalDate.class),
                rs.getInt("h"),
                rs.getLong("total"),
                rs.getInt("cnt")),
        householdId,
        currency,
        includeCash,
        ts(from),
        ts(to));
  }

  @Override
  public List<CategoryDay> categoryDayTotals(
      UUID householdId,
      String currency,
      Instant from,
      Instant to,
      boolean includeCash,
      @Nullable UUID parentId) {
    if (parentId == null) {
      return jdbc.query(
          CATEGORY_ROLLED,
          (rs, n) -> categoryDay(rs),
          UNCATEGORISED_EN,
          UNCATEGORISED_AR,
          householdId,
          currency,
          includeCash,
          ts(from),
          ts(to));
    }
    return jdbc.query(
        CATEGORY_DRILL,
        (rs, n) -> categoryDay(rs),
        householdId,
        currency,
        includeCash,
        ts(from),
        ts(to),
        parentId,
        parentId);
  }

  private static CategoryDay categoryDay(ResultSet rs) throws SQLException {
    return new CategoryDay(
        new CategoryRef(
            rs.getObject("cid", UUID.class),
            Objects.requireNonNull(rs.getString("name_en")),
            Objects.requireNonNull(rs.getString("name_ar")),
            rs.getString("color")),
        Objects.requireNonNull(rs.getObject("d", LocalDate.class)),
        rs.getLong("total"),
        rs.getInt("cnt"));
  }

  @Override
  public List<DayTotal> incomeDayTotals(
      UUID householdId, String currency, Instant from, Instant to) {
    return jdbc.query(
        INCOME,
        (rs, n) -> new DayTotal(rs.getObject("d", LocalDate.class), rs.getLong("total")),
        householdId,
        currency,
        ts(from),
        ts(to));
  }

  @Override
  public List<MerchantRow> merchants(
      UUID householdId, String currency, Instant from, Instant to, boolean includeCash, int limit) {
    return jdbc.query(
        MERCHANTS,
        (rs, n) ->
            new MerchantRow(
                Objects.requireNonNull(rs.getString("name")),
                rs.getLong("total"),
                rs.getInt("visits"),
                rs.getObject("category_id", UUID.class)),
        householdId,
        currency,
        includeCash,
        ts(from),
        ts(to),
        limit);
  }

  @Override
  public int pendingReviewCount(UUID householdId, Instant from, Instant to) {
    Integer n = jdbc.queryForObject(PENDING, Integer.class, householdId, ts(from), ts(to));
    return n == null ? 0 : n;
  }
}
