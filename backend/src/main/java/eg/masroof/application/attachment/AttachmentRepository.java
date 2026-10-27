package eg.masroof.application.attachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: attachment metadata. Every method is scoped to one household. */
public interface AttachmentRepository {

  /** One stored attachment row. */
  record Row(
      UUID id,
      UUID householdId,
      UUID expenseId,
      String storageKey,
      String contentType,
      long sizeBytes,
      String sha256,
      @Nullable UUID uploadedByMemberId,
      Instant createdAt) {}

  /** Outcome of {@link #insertIfRoom}. */
  enum InsertResult {
    INSERTED,
    FULL,
    NO_EXPENSE
  }

  /** Attachments of a live expense, oldest first. */
  List<Row> list(UUID householdId, UUID expenseId);

  /** Number of attachments of the expense. */
  int count(UUID householdId, UUID expenseId);

  /** One attachment of the expense, if it exists. */
  Optional<Row> find(UUID householdId, UUID expenseId, UUID attachmentId);

  /**
   * Atomically (expense row locked) inserts the row if the expense is live and has fewer than
   * {@code max} attachments.
   */
  InsertResult insertIfRoom(Row row, int max);

  /** Deletes the row; false when it did not exist. */
  boolean delete(UUID householdId, UUID expenseId, UUID attachmentId);
}
