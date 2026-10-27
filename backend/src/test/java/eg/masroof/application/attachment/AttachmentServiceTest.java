package eg.masroof.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eg.masroof.application.attachment.AttachmentRepository.InsertResult;
import eg.masroof.application.attachment.AttachmentRepository.Row;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.expense.ExpenseRepository;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.CurrentHousehold.Household;
import eg.masroof.application.sms.CallerMember;
import eg.masroof.domain.expense.Expense;
import eg.masroof.domain.expense.ExpenseSource;
import eg.masroof.domain.expense.ExpenseStatus;
import eg.masroof.domain.expense.ExpenseType;
import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Ordering and compensation rules of the upload use case, with in-memory ports. */
class AttachmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

  private final UUID household = UUID.randomUUID();
  private final UUID expenseId = UUID.randomUUID();
  private final Map<String, byte[]> objects = new HashMap<>();
  private final List<Row> rows = new ArrayList<>();
  private InsertResult nextInsert = InsertResult.INSERTED;
  private boolean insertThrows;
  private boolean putThrows;
  private boolean deleteThrows;
  private AttachmentService service;

  private final AttachmentStore store =
      new AttachmentStore() {
        @Override
        public void put(String key, String contentType, byte[] content) {
          if (putThrows) {
            throw new UnavailableException("down");
          }
          objects.put(key, content);
        }

        @Override
        public Optional<byte[]> get(String key) {
          return Optional.ofNullable(objects.get(key));
        }

        @Override
        public void delete(String key) {
          if (deleteThrows) {
            throw new UnavailableException("down");
          }
          objects.remove(key);
        }
      };

  private final AttachmentRepository repository =
      new AttachmentRepository() {
        @Override
        public List<Row> list(UUID householdId, UUID expense) {
          return rows;
        }

        @Override
        public int count(UUID householdId, UUID expense) {
          return rows.size();
        }

        @Override
        public Optional<Row> find(UUID householdId, UUID expense, UUID attachmentId) {
          return rows.stream().filter(r -> r.id().equals(attachmentId)).findFirst();
        }

        @Override
        public InsertResult insertIfRoom(Row row, int max) {
          if (insertThrows) {
            throw new IllegalStateException("db down");
          }
          if (nextInsert == InsertResult.INSERTED) {
            rows.add(row);
          }
          return nextInsert;
        }

        @Override
        public boolean delete(UUID householdId, UUID expense, UUID attachmentId) {
          return rows.removeIf(r -> r.id().equals(attachmentId));
        }
      };

  @BeforeEach
  void setUp() {
    ExpenseRepository expenses = mock(ExpenseRepository.class);
    when(expenses.find(household, expenseId)).thenReturn(Optional.of(expense(null)));
    CurrentHousehold current = () -> new Household(household, "EGP");
    CallerMember caller = Optional::empty;
    service =
        new AttachmentService(
            repository, store, expenses, current, caller, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Expense expense(@Nullable Instant deletedAt) {
    return new Expense(
        expenseId,
        household,
        ExpenseType.EXPENSE,
        Money.of(100, "EGP"),
        NOW,
        null,
        null,
        null,
        PaymentMethod.UNKNOWN,
        null,
        null,
        List.of(),
        ExpenseStatus.CONFIRMED,
        ExpenseSource.MANUAL,
        deletedAt,
        NOW,
        NOW);
  }

  private static byte[] pdf() {
    return "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 object first, then row; the key holds ids only")
  void happyPath() {
    Row row = service.upload(expenseId, pdf());
    assertThat(rows).containsExactly(row);
    assertThat(objects).containsOnlyKeys("households/" + household + "/attachments/" + row.id());
    assertThat(row.contentType()).isEqualTo("application/pdf");
    assertThat(row.sizeBytes()).isEqualTo(pdf().length);
    assertThat(row.sha256()).hasSize(64);
    assertThat(row.createdAt()).isEqualTo(NOW);
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 when the insert fails the stored object is removed again")
  void insertFailureRemovesObject() {
    insertThrows = true;
    assertThatThrownBy(() -> service.upload(expenseId, pdf()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(objects).isEmpty();
    assertThat(rows).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-021")
  @DisplayName("REQ-RCPT-021 losing the count race removes the object and answers 409")
  void raceLost() {
    nextInsert = InsertResult.FULL;
    assertThatThrownBy(() -> service.upload(expenseId, pdf()))
        .isInstanceOfSatisfying(
            ExpenseException.class,
            e -> assertThat(e.kind()).isEqualTo(ExpenseException.Kind.CONFLICT));
    assertThat(objects).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName(
      "REQ-RCPT-023 an expense deleted during the upload removes the object and answers 404")
  void expenseGoneMidUpload() {
    nextInsert = InsertResult.NO_EXPENSE;
    assertThatThrownBy(() -> service.upload(expenseId, pdf()))
        .isInstanceOfSatisfying(
            ExpenseException.class,
            e -> assertThat(e.kind()).isEqualTo(ExpenseException.Kind.NOT_FOUND));
    assertThat(objects).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 unavailable storage is 503 and no row is written")
  void storageDown() {
    putThrows = true;
    assertThatThrownBy(() -> service.upload(expenseId, pdf()))
        .isInstanceOfSatisfying(
            AttachmentException.class,
            e -> assertThat(e.kind()).isEqualTo(AttachmentException.Kind.STORAGE_UNAVAILABLE));
    assertThat(rows).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-021")
  @DisplayName(
      "REQ-RCPT-021 empty, oversize and unsupported files are rejected before anything is stored")
  void rejectsBeforeStoring() {
    assertThatThrownBy(() -> service.upload(expenseId, new byte[0]))
        .isInstanceOfSatisfying(
            AttachmentException.class,
            e -> assertThat(e.kind()).isEqualTo(AttachmentException.Kind.EMPTY));
    assertThatThrownBy(() -> service.upload(expenseId, new byte[AttachmentService.MAX_BYTES + 1]))
        .isInstanceOfSatisfying(
            AttachmentException.class,
            e -> assertThat(e.kind()).isEqualTo(AttachmentException.Kind.TOO_LARGE));
    assertThatThrownBy(
            () -> service.upload(expenseId, "GIF89a".getBytes(StandardCharsets.ISO_8859_1)))
        .isInstanceOfSatisfying(
            AttachmentException.class,
            e -> assertThat(e.kind()).isEqualTo(AttachmentException.Kind.UNSUPPORTED_TYPE));
    assertThat(objects).isEmpty();
    assertThat(rows).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-025")
  @DisplayName("REQ-RCPT-025 an object-delete failure does not fail the removal")
  void deleteToleratesObjectFailure() {
    Row row = service.upload(expenseId, pdf());
    deleteThrows = true;
    service.delete(expenseId, row.id());
    assertThat(rows).isEmpty();
    assertThatThrownBy(() -> service.delete(expenseId, row.id()))
        .isInstanceOf(ExpenseException.class);
  }
}
