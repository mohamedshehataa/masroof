package eg.masroof.application.sms;

import eg.masroof.domain.expense.ExpenseSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: raw message audit trail and the job queue built on it. */
public interface RawMessageRepository {

  /** A device-uploaded SMS to store; {@code now} stamps the queue entry. */
  record NewSms(
      UUID id,
      UUID householdId,
      @Nullable UUID memberId,
      UUID deviceId,
      String externalId,
      String sender,
      String body,
      Instant receivedAt,
      Instant now) {

    @Override
    public String toString() {
      return "NewSms[id=" + id + "]";
    }
  }

  /**
   * A structured on-device parse to record. No text is stored: the body column holds an empty
   * string and {@code sender} is the allowlisted sender id.
   */
  record NewParsed(
      UUID id,
      UUID householdId,
      @Nullable UUID memberId,
      ExpenseSource source,
      UUID deviceId,
      String localId,
      String sender,
      Instant receivedAt,
      Instant now) {}

  /** The row for a (household, device, external id), and whether this call created it. */
  record Stored(UUID id, boolean inserted) {}

  /** Redacted view of the message behind an expense. */
  record SourceView(
      UUID rawMessageId,
      ExpenseSource source,
      @Nullable String sender,
      @Nullable UUID memberId,
      Instant receivedAt,
      String processingStatus) {}

  /** Stores the evidence row of a parsed record as DONE (never queued: nothing to parse). */
  Stored insertParsed(NewParsed parsed);

  /** Links the expense created from a parsed record to its evidence row. */
  void attachExpense(UUID rawMessageId, UUID expenseId);

  /** Stores the message as QUEUED; an existing (device, id) pair is returned untouched. */
  Stored insertSms(NewSms sms);

  /**
   * Claims the oldest due QUEUED row with {@code FOR UPDATE SKIP LOCKED}, counts the attempt and
   * leases it for {@code lease} so a crashed worker's row comes back later.
   */
  Optional<RawMessage> claimNext(Instant now, Duration lease);

  /** Re-reads a claimed row under a row lock; empty when it is no longer QUEUED. */
  Optional<RawMessage> lockQueued(UUID id);

  /** Ends processing. {@code scrubBody} replaces the text (OTP codes are never kept). */
  void finish(
      UUID id,
      String status,
      @Nullable UUID expenseId,
      @Nullable String templateId,
      @Nullable String errorCode,
      boolean scrubBody,
      Instant now);

  /** Schedules another attempt, or ends the row as FAILED when {@code dead}. */
  void retryLater(UUID id, Instant nextAttemptAt, boolean dead, String errorCode, Instant now);

  /** The raw messages that evidence the given expenses, keyed by expense id. */
  Map<UUID, SourceView> sourcesOf(UUID householdId, Collection<UUID> expenseIds);
}
