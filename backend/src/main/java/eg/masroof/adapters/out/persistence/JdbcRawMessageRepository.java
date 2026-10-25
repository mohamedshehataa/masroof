package eg.masroof.adapters.out.persistence;

import eg.masroof.application.sms.RawMessage;
import eg.masroof.application.sms.RawMessageRepository;
import eg.masroof.application.sms.SmsAccounts;
import eg.masroof.domain.expense.ExpenseSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of the raw message ports. Message text is only ever bound as a parameter and
 * never concatenated into SQL or written to a log.
 */
@Repository
class JdbcRawMessageRepository implements RawMessageRepository, SmsAccounts {

  private static final String INSERT_SMS =
      """
      insert into raw_message (id, household_id, member_id, source, device_id, external_id,
        sender, body, received_at, processing_status, next_attempt_at, created_at, updated_at)
      values (?, ?, ?, 'SMS', ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)
      on conflict (household_id, source, device_id, external_id)
        where device_id is not null and external_id is not null
      do nothing
      returning id
      """;

  private static final String FIND_SMS =
      """
      select id from raw_message
      where household_id = ? and source = 'SMS' and device_id = ? and external_id = ?
      """;

  private static final String CLAIM =
      """
      update raw_message set attempts = attempts + 1, next_attempt_at = ?
      where id = (
        select id from raw_message
        where processing_status = 'QUEUED' and next_attempt_at <= ?
        order by next_attempt_at, id
        limit 1
        for update skip locked)
      returning id, household_id, member_id, source, sender, body, received_at, attempts,
        (select base_currency from household h where h.id = raw_message.household_id)
          as base_currency
      """;

  private static final String LOCK =
      """
      select r.id, r.household_id, r.member_id, r.source, r.sender, r.body, r.received_at,
        r.attempts, h.base_currency
      from raw_message r join household h on h.id = r.household_id
      where r.id = ? and r.processing_status = 'QUEUED'
      for update of r
      """;

  private static final String FINISH =
      """
      update raw_message set processing_status = ?, expense_id = ?, template_id = ?,
        error_code = ?, body = case when ? then '[redacted]' else body end
      where id = ?
      """;

  private static final String RETRY =
      """
      update raw_message set processing_status = case when ? then 'FAILED' else 'QUEUED' end,
        next_attempt_at = ?, error_code = ?
      where id = ?
      """;

  private static final String SOURCES =
      """
      select expense_id, id, source, sender, member_id, received_at, processing_status
      from raw_message
      where household_id = ? and expense_id = any (?::uuid[])
      """;

  private static final String ACCOUNT_BY_LAST4 =
      """
      select id, member_id from payment_account where household_id = ? and last4 = ? limit 2
      """;

  private static final RowMapper<RawMessage> RAW =
      (rs, n) ->
          new RawMessage(
              rs.getObject("id", UUID.class),
              rs.getObject("household_id", UUID.class),
              rs.getObject("member_id", UUID.class),
              ExpenseSource.valueOf(rs.getString("source")),
              rs.getString("sender"),
              rs.getString("body"),
              instant(rs, "received_at"),
              rs.getInt("attempts"),
              rs.getString("base_currency"));

  private final JdbcTemplate jdbc;

  JdbcRawMessageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    if (value == null) {
      throw new SQLException("unexpected null in " + column);
    }
    return value.toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  @Override
  public Stored insertSms(NewSms s) {
    List<UUID> created =
        jdbc.queryForList(
            INSERT_SMS,
            UUID.class,
            s.id(),
            s.householdId(),
            s.memberId(),
            s.deviceId(),
            s.externalId(),
            s.sender(),
            s.body(),
            utc(s.receivedAt()),
            utc(s.now()),
            utc(s.now()),
            utc(s.now()));
    if (!created.isEmpty()) {
      return new Stored(created.get(0), true);
    }
    UUID existing =
        jdbc.queryForObject(FIND_SMS, UUID.class, s.householdId(), s.deviceId(), s.externalId());
    return new Stored(Objects.requireNonNull(existing), false);
  }

  @Override
  public Optional<RawMessage> claimNext(Instant now, Duration lease) {
    return jdbc.query(CLAIM, RAW, utc(now.plus(lease)), utc(now)).stream().findFirst();
  }

  @Override
  public Optional<RawMessage> lockQueued(UUID id) {
    return jdbc.query(LOCK, RAW, id).stream().findFirst();
  }

  @Override
  public void finish(
      UUID id,
      String status,
      @Nullable UUID expenseId,
      @Nullable String templateId,
      @Nullable String errorCode,
      boolean scrubBody,
      Instant now) {
    jdbc.update(FINISH, status, expenseId, templateId, errorCode, scrubBody, id);
  }

  @Override
  public void retryLater(
      UUID id, Instant nextAttemptAt, boolean dead, String errorCode, Instant now) {
    jdbc.update(RETRY, dead, utc(nextAttemptAt), errorCode, id);
  }

  @Override
  public Map<UUID, SourceView> sourcesOf(UUID householdId, Collection<UUID> expenseIds) {
    if (expenseIds.isEmpty()) {
      return Map.of();
    }
    String array =
        expenseIds.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    Map<UUID, SourceView> out = new HashMap<>();
    jdbc.query(
        SOURCES,
        rs -> {
          out.put(
              rs.getObject("expense_id", UUID.class),
              new SourceView(
                  rs.getObject("id", UUID.class),
                  ExpenseSource.valueOf(rs.getString("source")),
                  rs.getString("sender"),
                  rs.getObject("member_id", UUID.class),
                  instant(rs, "received_at"),
                  rs.getString("processing_status")));
        },
        householdId,
        array);
    return out;
  }

  @Override
  public Optional<AccountRef> findByLast4(UUID householdId, String last4) {
    List<AccountRef> found =
        jdbc.query(
            ACCOUNT_BY_LAST4,
            (rs, n) ->
                new AccountRef(
                    rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class)),
            householdId,
            last4);
    return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
  }
}
