package eg.masroof.application.attachment;

import eg.masroof.application.attachment.AttachmentException.Kind;
import eg.masroof.application.attachment.AttachmentRepository.InsertResult;
import eg.masroof.application.attachment.AttachmentRepository.Row;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.expense.ExpenseRepository;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.sms.CallerMember;
import eg.masroof.domain.attachment.AttachmentType;
import eg.masroof.domain.attachment.MetadataStripper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Use cases for receipt attachments (REQ-RCPT-020..026). Never logs file bytes. */
@Service
public class AttachmentService {

  public static final int MAX_BYTES = 8 * 1024 * 1024;
  public static final int MAX_PER_EXPENSE = 5;

  private static final Logger LOG = LoggerFactory.getLogger(AttachmentService.class);

  /** The bytes of one attachment with the type to serve them as. */
  @SuppressWarnings("ArrayRecordComponent") // one-shot carrier, never compared
  public record Content(String contentType, byte[] bytes) {}

  private final AttachmentRepository attachments;
  private final AttachmentStore store;
  private final ExpenseRepository expenses;
  private final CurrentHousehold currentHousehold;
  private final CallerMember caller;
  private final Clock clock;

  AttachmentService(
      AttachmentRepository attachments,
      AttachmentStore store,
      ExpenseRepository expenses,
      CurrentHousehold currentHousehold,
      CallerMember caller,
      Clock clock) {
    this.attachments = attachments;
    this.store = store;
    this.expenses = expenses;
    this.currentHousehold = currentHousehold;
    this.caller = caller;
    this.clock = clock;
  }

  /** Attachments of a live expense, oldest first. */
  public List<Row> list(UUID expenseId) {
    UUID household = requireLiveExpense(expenseId);
    return attachments.list(household, expenseId);
  }

  /** Validates, strips and stores one file; the object is written before the row. */
  public Row upload(UUID expenseId, byte[] raw) {
    UUID household = requireLiveExpense(expenseId);
    if (raw.length == 0) {
      throw new AttachmentException(Kind.EMPTY, "The file is empty");
    }
    if (raw.length > MAX_BYTES) {
      throw new AttachmentException(Kind.TOO_LARGE, "The file is larger than 8 MiB");
    }
    AttachmentType type =
        AttachmentType.sniff(raw)
            .orElseThrow(
                () ->
                    new AttachmentException(
                        Kind.UNSUPPORTED_TYPE, "Only JPEG, PNG and PDF files are accepted"));
    byte[] clean;
    try {
      clean = MetadataStripper.strip(type, raw);
    } catch (MetadataStripper.MalformedImageException e) {
      throw new AttachmentException(Kind.UNSUPPORTED_TYPE, "The image file is damaged");
    }
    if (attachments.count(household, expenseId) >= MAX_PER_EXPENSE) {
      throw tooMany();
    }
    UUID id = UUID.randomUUID();
    String key = "households/" + household + "/attachments/" + id;
    try {
      store.put(key, type.contentType(), clean);
    } catch (AttachmentStore.UnavailableException e) {
      throw unavailable();
    }
    Row row =
        new Row(
            id,
            household,
            expenseId,
            key,
            type.contentType(),
            clean.length,
            sha256(clean),
            caller.memberId().orElse(null),
            clock.instant());
    InsertResult result;
    try {
      result = attachments.insertIfRoom(row, MAX_PER_EXPENSE);
    } catch (RuntimeException e) {
      removeObject(key, id);
      throw e;
    }
    if (result == InsertResult.INSERTED) {
      return row;
    }
    removeObject(key, id);
    throw result == InsertResult.FULL ? tooMany() : ExpenseException.notFound("Expense");
  }

  /** The stored bytes of one attachment of the expense. */
  public Content download(UUID expenseId, UUID attachmentId) {
    UUID household = requireLiveExpense(expenseId);
    Row row = requireAttachment(household, expenseId, attachmentId);
    try {
      byte[] bytes =
          store.get(row.storageKey()).orElseThrow(() -> ExpenseException.notFound("Attachment"));
      return new Content(row.contentType(), bytes);
    } catch (AttachmentStore.UnavailableException e) {
      throw unavailable();
    }
  }

  /** Removes the row, then the object (an object failure is logged, not raised). */
  public void delete(UUID expenseId, UUID attachmentId) {
    UUID household = requireLiveExpense(expenseId);
    Row row = requireAttachment(household, expenseId, attachmentId);
    if (!attachments.delete(household, expenseId, attachmentId)) {
      throw ExpenseException.notFound("Attachment");
    }
    removeObject(row.storageKey(), row.id());
  }

  private UUID requireLiveExpense(UUID expenseId) {
    UUID household = currentHousehold.get().id();
    boolean live =
        expenses.find(household, expenseId).filter(e -> e.deletedAt() == null).isPresent();
    if (!live) {
      throw ExpenseException.notFound("Expense");
    }
    return household;
  }

  private Row requireAttachment(UUID household, UUID expenseId, UUID attachmentId) {
    return attachments
        .find(household, expenseId, attachmentId)
        .orElseThrow(() -> ExpenseException.notFound("Attachment"));
  }

  private void removeObject(String key, UUID attachmentId) {
    try {
      store.delete(key);
    } catch (RuntimeException e) {
      LOG.warn("Could not delete the stored object of attachment {}", attachmentId);
    }
  }

  private static ExpenseException tooMany() {
    return new ExpenseException(
        ExpenseException.Kind.CONFLICT,
        "Too many attachments",
        "An expense can have at most " + MAX_PER_EXPENSE + " attachments",
        List.of(),
        "attachment-limit");
  }

  private static AttachmentException unavailable() {
    return new AttachmentException(Kind.STORAGE_UNAVAILABLE, "Attachment storage is not available");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
