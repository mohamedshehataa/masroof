package eg.masroof.adapters.out.persistence;

import eg.masroof.application.auth.Account;
import eg.masroof.application.auth.InviteStore;
import eg.masroof.domain.auth.InviteCode;
import eg.masroof.domain.auth.Role;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Invites in Postgres; household-scoped, with the household row as the serialisation point. */
@Repository
class JdbcInviteStore implements InviteStore {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  JdbcInviteStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactions);
  }

  @Override
  public CreateResult create(NewInvite invite, int maxPending, int maxMembers, Instant now) {
    CreateResult result =
        tx.execute(
            status -> {
              jdbc.queryForList(
                  "select id from household where id = ? for update", invite.householdId());
              Integer members =
                  jdbc.queryForObject(
                      "select count(*) from member where household_id = ?",
                      Integer.class,
                      invite.householdId());
              if (members != null && members >= maxMembers) {
                return CreateResult.HOUSEHOLD_FULL;
              }
              Integer pending =
                  jdbc.queryForObject(
                      """
                      select count(*) from invite
                      where household_id = ? and status = 'PENDING' and expires_at > ?
                      """,
                      Integer.class,
                      invite.householdId(),
                      utc(now));
              if (pending != null && pending >= maxPending) {
                return CreateResult.TOO_MANY_PENDING;
              }
              jdbc.update(
                  """
                  insert into invite (id, household_id, created_by, code_hash, display_name,
                                      email, phone_e164, created_at, expires_at)
                  values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                  """,
                  invite.id(),
                  invite.householdId(),
                  invite.createdBy(),
                  invite.codeHash(),
                  invite.displayName(),
                  invite.email(),
                  invite.phoneE164(),
                  utc(invite.createdAt()),
                  utc(invite.expiresAt()));
              return CreateResult.CREATED;
            });
    return result == null ? CreateResult.HOUSEHOLD_FULL : result;
  }

  @Override
  public List<InviteView> listActive(UUID householdId, Instant now) {
    return jdbc.query(
        """
        select id, display_name, email, phone_e164, status, created_at, expires_at
        from invite
        where household_id = ? and status = 'PENDING' and expires_at > ?
        order by created_at desc, id
        """,
        (rs, n) ->
            new InviteView(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("phone_e164"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
        householdId,
        utc(now));
  }

  @Override
  public RevokeResult revoke(UUID householdId, UUID inviteId, Instant now) {
    int changed =
        jdbc.update(
            """
            update invite set status = 'REVOKED', revoked_at = ?
            where id = ? and household_id = ? and status = 'PENDING'
            """,
            utc(now),
            inviteId,
            householdId);
    if (changed > 0) {
      return RevokeResult.REVOKED;
    }
    List<String> status =
        jdbc.queryForList(
            "select status from invite where id = ? and household_id = ?",
            String.class,
            inviteId,
            householdId);
    if (status.isEmpty()) {
      return RevokeResult.NOT_FOUND;
    }
    return "ACCEPTED".equals(status.get(0)) ? RevokeResult.ACCEPTED : RevokeResult.ALREADY_REVOKED;
  }

  @Override
  public boolean claimable(String codeHash, Instant now, String email) {
    Integer found =
        jdbc.queryForObject(
            """
            select count(*) from invite
            where code_hash = ? and status = 'PENDING' and expires_at > ?
              and (email is null or lower(email) = lower(?))
            """,
            Integer.class,
            codeHash,
            utc(now),
            email);
    return found != null && found > 0;
  }

  @Override
  public AcceptResult accept(
      String codeHash,
      Instant now,
      String email,
      @Nullable String displayName,
      String passwordHash,
      int maxMembers) {
    try {
      AcceptResult result =
          tx.execute(
              status ->
                  acceptInTx(status, codeHash, now, email, displayName, passwordHash, maxMembers));
      return result == null ? new AcceptResult.Invalid() : result;
    } catch (DuplicateKeyException e) {
      return new AcceptResult.EmailTaken(); // lost a race on the unique e-mail index; rolled back
    }
  }

  private AcceptResult acceptInTx(
      org.springframework.transaction.TransactionStatus status,
      String codeHash,
      Instant now,
      String email,
      @Nullable String displayName,
      String passwordHash,
      int maxMembers) {
    // The conditional update is the atomic claim: a concurrent second accept waits on the row
    // lock, then no longer matches status = 'PENDING'.
    List<UUID[]> claimed =
        jdbc.query(
            """
            update invite set status = 'ACCEPTED', accepted_at = ?
            where code_hash = ? and status = 'PENDING' and expires_at > ?
              and (email is null or lower(email) = lower(?))
            returning id, household_id
            """,
            (rs, n) ->
                new UUID[] {
                  rs.getObject("id", UUID.class), rs.getObject("household_id", UUID.class)
                },
            utc(now),
            codeHash,
            utc(now),
            email);
    if (claimed.isEmpty()) {
      return new AcceptResult.Invalid();
    }
    UUID inviteId = claimed.get(0)[0];
    UUID household = claimed.get(0)[1];
    String storedHash =
        jdbc.queryForObject("select code_hash from invite where id = ?", String.class, inviteId);
    if (storedHash == null || !InviteCode.constantTimeEquals(storedHash.strip(), codeHash)) {
      status.setRollbackOnly();
      return new AcceptResult.Invalid();
    }
    jdbc.queryForList("select id from household where id = ? for update", household);
    Integer members =
        jdbc.queryForObject(
            "select count(*) from member where household_id = ?", Integer.class, household);
    if (members != null && members >= maxMembers) {
      status.setRollbackOnly();
      return new AcceptResult.Full();
    }
    Integer taken =
        jdbc.queryForObject(
            "select count(*) from app_user where lower(email) = lower(?)", Integer.class, email);
    if (taken != null && taken > 0) {
      status.setRollbackOnly();
      return new AcceptResult.EmailTaken();
    }
    String name =
        displayName != null
            ? displayName
            : jdbc.queryForObject(
                "select display_name from invite where id = ?", String.class, inviteId);
    UUID member = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    jdbc.update(
        "insert into member (id, household_id, display_name, role) values (?, ?, ?, 'MEMBER')",
        member,
        household,
        name);
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, ?, ?)",
        user,
        member,
        email,
        passwordHash);
    jdbc.update("update invite set accepted_member_id = ? where id = ?", member, inviteId);
    String currency =
        jdbc.queryForObject(
            "select base_currency from household where id = ?", String.class, household);
    return new AcceptResult.Accepted(
        new Account(
            user,
            member,
            household,
            currency == null ? "EGP" : currency.strip(),
            name == null ? "" : name,
            Role.MEMBER,
            passwordHash));
  }

  @Override
  public List<MemberView> members(UUID householdId) {
    return jdbc.query(
        """
        select id, household_id, display_name, role from member
        where household_id = ? order by created_at, id
        """,
        (rs, n) ->
            new MemberView(
                rs.getObject("id", UUID.class),
                rs.getObject("household_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("role")),
        householdId);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
