package eg.masroof.adapters.out.persistence;

import eg.masroof.application.attachment.AttachmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC implementation of {@link AttachmentRepository}; every statement is household-scoped. */
@Repository
class JdbcAttachmentRepository implements AttachmentRepository {

  private static final String COLUMNS =
      "id, household_id, expense_id, storage_key, content_type, size_bytes, sha256,"
          + " uploaded_by_member_id, created_at";

  private static final String LIST =
      "select "
          + COLUMNS
          + " from expense_attachment where household_id = ? and expense_id = ?"
          + " order by created_at, id";

  private static final String FIND =
      "select "
          + COLUMNS
          + " from expense_attachment where household_id = ? and expense_id = ? and id = ?";

  private static final String COUNT =
      "select count(*) from expense_attachment where household_id = ? and expense_id = ?";

  private static final String LOCK_EXPENSE =
      "select id from expense where household_id = ? and id = ? and deleted_at is null for update";

  private static final String INSERT =
      """
      insert into expense_attachment (id, household_id, expense_id, storage_key, content_type,
        size_bytes, sha256, uploaded_by_member_id, created_at)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String DELETE =
      "delete from expense_attachment where household_id = ? and expense_id = ? and id = ?";

  private static final RowMapper<Row> MAPPER = JdbcAttachmentRepository::map;

  private final JdbcTemplate jdbc;

  JdbcAttachmentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Row map(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("household_id", UUID.class),
        rs.getObject("expense_id", UUID.class),
        rs.getString("storage_key"),
        rs.getString("content_type"),
        rs.getLong("size_bytes"),
        rs.getString("sha256"),
        rs.getObject("uploaded_by_member_id", UUID.class),
        Objects.requireNonNull(rs.getObject("created_at", OffsetDateTime.class)).toInstant());
  }

  @Override
  public List<Row> list(UUID householdId, UUID expenseId) {
    return jdbc.query(LIST, MAPPER, householdId, expenseId);
  }

  @Override
  public int count(UUID householdId, UUID expenseId) {
    Integer n = jdbc.queryForObject(COUNT, Integer.class, householdId, expenseId);
    return n == null ? 0 : n;
  }

  @Override
  public Optional<Row> find(UUID householdId, UUID expenseId, UUID attachmentId) {
    return jdbc.query(FIND, MAPPER, householdId, expenseId, attachmentId).stream().findFirst();
  }

  @Override
  @Transactional
  public InsertResult insertIfRoom(Row row, int max) {
    if (jdbc.queryForList(LOCK_EXPENSE, row.householdId(), row.expenseId()).isEmpty()) {
      return InsertResult.NO_EXPENSE;
    }
    if (count(row.householdId(), row.expenseId()) >= max) {
      return InsertResult.FULL;
    }
    jdbc.update(
        INSERT,
        row.id(),
        row.householdId(),
        row.expenseId(),
        row.storageKey(),
        row.contentType(),
        row.sizeBytes(),
        row.sha256(),
        row.uploadedByMemberId(),
        OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
    return InsertResult.INSERTED;
  }

  @Override
  public boolean delete(UUID householdId, UUID expenseId, UUID attachmentId) {
    return jdbc.update(DELETE, householdId, expenseId, attachmentId) > 0;
  }
}
