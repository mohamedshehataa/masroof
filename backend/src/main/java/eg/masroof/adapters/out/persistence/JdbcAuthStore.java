package eg.masroof.adapters.out.persistence;

import eg.masroof.application.auth.Account;
import eg.masroof.application.auth.AccountStore;
import eg.masroof.application.auth.RefreshTokenStore;
import eg.masroof.domain.auth.Role;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accounts and refresh tokens in Postgres. */
@Repository
class JdbcAuthStore implements AccountStore, RefreshTokenStore {

  private static final String ACCOUNT_SELECT =
      "select u.id as user_id, m.id as member_id, h.id as household_id, h.base_currency,"
          + " m.display_name, m.role, u.password_hash"
          + " from app_user u join member m on m.id = u.member_id"
          + " join household h on h.id = m.household_id ";

  private static final RowMapper<Account> ACCOUNT =
      (rs, n) ->
          new Account(
              rs.getObject("user_id", UUID.class),
              rs.getObject("member_id", UUID.class),
              rs.getObject("household_id", UUID.class),
              rs.getString("base_currency").strip(),
              rs.getString("display_name"),
              Role.valueOf(rs.getString("role")),
              rs.getString("password_hash"));

  private final JdbcTemplate jdbc;

  JdbcAuthStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Account> findByEmail(String email) {
    return jdbc.query(ACCOUNT_SELECT + "where lower(u.email) = lower(?)", ACCOUNT, email).stream()
        .findFirst();
  }

  @Override
  public Optional<Account> findByUserId(UUID userId) {
    return jdbc.query(ACCOUNT_SELECT + "where u.id = ?", ACCOUNT, userId).stream().findFirst();
  }

  @Override
  @Transactional
  public boolean createFirstAdmin(String email, String displayName, String passwordHash) {
    jdbc.execute("select pg_advisory_xact_lock(90091)");
    Integer users = jdbc.queryForObject("select count(*) from app_user", Integer.class);
    if (users != null && users > 0) {
      return false;
    }
    List<UUID> households =
        jdbc.query(
            "select id from household order by created_at, id limit 1",
            (rs, n) -> rs.getObject("id", UUID.class));
    UUID household;
    if (households.isEmpty()) {
      household = UUID.randomUUID();
      jdbc.update("insert into household (id, name) values (?, 'Masroof household')", household);
      jdbc.query("select seed_household_categories(?)", rs -> {}, household);
    } else {
      household = households.get(0);
    }
    UUID member = UUID.randomUUID();
    jdbc.update(
        "insert into member (id, household_id, display_name, role) values (?, ?, ?, 'ADMIN')",
        member,
        household,
        displayName);
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, ?, ?)",
        UUID.randomUUID(),
        member,
        email,
        passwordHash);
    return true;
  }

  @Override
  public void insertNewFamily(UUID userId, String tokenHash, Instant expiresAt) {
    UUID id = UUID.randomUUID();
    insertToken(id, userId, id, tokenHash, expiresAt);
  }

  @Override
  @Transactional
  public Outcome rotate(String oldHash, String newHash, Instant newExpiresAt, Instant now) {
    List<Row> rows =
        jdbc.query(
            "select id, user_id, family_id, expires_at, revoked_at, rotated_at"
                + " from refresh_token where token_hash = ? for update",
            (rs, n) ->
                new Row(
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("family_id", UUID.class),
                    rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("revoked_at") != null,
                    rs.getObject("rotated_at") != null),
            oldHash);
    if (rows.isEmpty()) {
      return new Outcome.Invalid();
    }
    Row row = rows.get(0);
    if (row.rotated()) {
      revokeFamilyById(row.familyId(), now);
      jdbc.update(
          "insert into audit_log (id, household_id, member_id, action)"
              + " select ?, m.household_id, m.id, 'REFRESH_REUSE'"
              + " from app_user u join member m on m.id = u.member_id where u.id = ?",
          UUID.randomUUID(),
          row.userId());
      return new Outcome.Reuse();
    }
    if (row.revoked()) {
      return new Outcome.Invalid();
    }
    if (!now.isBefore(row.expiresAt())) {
      return new Outcome.Expired();
    }
    jdbc.update("update refresh_token set rotated_at = ? where token_hash = ?", utc(now), oldHash);
    insertToken(UUID.randomUUID(), row.userId(), row.familyId(), newHash, newExpiresAt);
    return new Outcome.Rotated(row.userId());
  }

  @Override
  public void revokeFamily(String tokenHash, UUID userId, Instant now) {
    jdbc.update(
        "update refresh_token set revoked_at = ? where revoked_at is null and family_id ="
            + " (select family_id from refresh_token where token_hash = ? and user_id = ?)",
        utc(now),
        tokenHash,
        userId);
  }

  @Override
  public void revokeAll(UUID userId, Instant now) {
    jdbc.update(
        "update refresh_token set revoked_at = ? where user_id = ? and revoked_at is null",
        utc(now),
        userId);
  }

  private void revokeFamilyById(UUID familyId, Instant now) {
    jdbc.update(
        "update refresh_token set revoked_at = ? where family_id = ? and revoked_at is null",
        utc(now),
        familyId);
  }

  private void insertToken(UUID id, UUID userId, UUID family, String hash, Instant expiresAt) {
    jdbc.update(
        "insert into refresh_token (id, user_id, family_id, token_hash, expires_at)"
            + " values (?, ?, ?, ?, ?)",
        id,
        userId,
        family,
        hash,
        utc(expiresAt));
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private record Row(
      UUID userId, UUID familyId, Instant expiresAt, boolean revoked, boolean rotated) {}
}
