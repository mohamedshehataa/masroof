package eg.masroof.application.expense;

import eg.masroof.application.expense.ExpenseException.Issue;
import eg.masroof.application.expense.ExpenseException.Kind;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.CurrentHousehold.Household;
import eg.masroof.domain.expense.BusinessCalendar;
import eg.masroof.domain.expense.Expense;
import eg.masroof.domain.expense.ExpenseSource;
import eg.masroof.domain.expense.ExpenseStatus;
import eg.masroof.domain.expense.ExpenseType;
import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for manually entered expenses (REQ-EXP-001, -002, -003, -004, -020, -021, -023). */
@Service
public class ExpenseService {

  static final long MAX_AMOUNT_MINOR = 10_000_000_000_000L;
  static final int MAX_NOTES = 500;
  static final int MAX_TAGS = 10;
  static final int MAX_TAG_LENGTH = 30;
  static final Duration MAX_FUTURE = Duration.ofDays(1);
  static final Instant MIN_OCCURRED_AT = Instant.parse("2000-01-01T00:00:00Z");
  static final int MAX_MERCHANT = 200;
  static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

  /** Fields of a new expense; the adapter has already mapped transport types. */
  public record CreateCommand(
      ExpenseType type,
      Money amount,
      Instant occurredAt,
      @Nullable UUID categoryId,
      @Nullable UUID paidByMemberId,
      @Nullable UUID paymentAccountId,
      @Nullable PaymentMethod paymentMethod,
      @Nullable String merchantRaw,
      @Nullable String notes,
      List<String> tags) {}

  /** Partial update: a null field means "leave unchanged". */
  public record PatchCommand(
      @Nullable ExpenseType type,
      @Nullable Money amount,
      @Nullable Instant occurredAt,
      @Nullable UUID categoryId,
      @Nullable UUID paidByMemberId,
      @Nullable UUID paymentAccountId,
      @Nullable PaymentMethod paymentMethod,
      @Nullable ExpenseStatus status,
      @Nullable String merchantRaw,
      @Nullable String notes,
      @Nullable List<String> tags) {}

  /** Query parameters of the list endpoint after transport parsing. */
  public record ListQuery(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable UUID memberId,
      @Nullable UUID categoryId,
      boolean merchantFilter,
      @Nullable UUID accountId,
      @Nullable ExpenseSource source,
      @Nullable ExpenseStatus status,
      @Nullable ExpenseType type,
      @Nullable Long minAmountMinor,
      @Nullable Long maxAmountMinor,
      @Nullable String text,
      boolean includeDeleted,
      String sort,
      @Nullable String cursor,
      int limit) {}

  /** A page of results. */
  public record Page(List<Expense> items, @Nullable String nextCursor, Money total) {}

  /** The created row and whether it is new (false on an idempotent replay). */
  public record Created(Expense expense, boolean replay) {}

  private final ExpenseRepository expenses;
  private final ReferenceData reference;
  private final CurrentHousehold currentHousehold;
  private final Clock clock;

  ExpenseService(
      ExpenseRepository expenses,
      ReferenceData reference,
      CurrentHousehold currentHousehold,
      Clock clock) {
    this.expenses = expenses;
    this.reference = reference;
    this.currentHousehold = currentHousehold;
    this.clock = clock;
  }

  /** Creates a CONFIRMED MANUAL entry; replays the original for a repeated idempotency key. */
  @Transactional
  public Created create(
      CreateCommand cmd, @Nullable String idempotencyKey, @Nullable String fingerprint) {
    Household household = currentHousehold.get();
    Instant now = clock.instant();
    if (idempotencyKey != null && fingerprint != null) {
      Optional<ExpenseRepository.IdempotencyRecord> seen =
          expenses.findIdempotency(household.id(), idempotencyKey);
      if (seen.isPresent() && seen.get().createdAt().isAfter(now.minus(IDEMPOTENCY_TTL))) {
        if (!seen.get().fingerprint().equals(fingerprint)) {
          throw new ExpenseException(
              Kind.CONFLICT,
              "Idempotency key reused",
              "The Idempotency-Key was already used with a different request",
              List.of());
        }
        Expense original =
            expenses
                .find(household.id(), seen.get().expenseId())
                .filter(e -> e.deletedAt() == null)
                .orElseThrow(
                    () ->
                        new ExpenseException(
                            Kind.CONFLICT,
                            "Idempotency key reused",
                            "The expense created with this Idempotency-Key was deleted;"
                                + " use a new key to create it again",
                            List.of()));
        return new Created(original, true);
      }
    }
    validateMoney(cmd.amount(), household);
    validateOccurredAt(cmd.occurredAt(), now);
    validateText(cmd.notes(), cmd.merchantRaw(), cmd.tags());
    validateReferences(household, cmd.categoryId(), cmd.paidByMemberId(), cmd.paymentAccountId());
    Expense row =
        new Expense(
            UUID.randomUUID(),
            household.id(),
            cmd.type(),
            cmd.amount(),
            cmd.occurredAt(),
            cmd.categoryId(),
            cmd.paymentAccountId(),
            cmd.paidByMemberId(),
            cmd.paymentMethod() == null ? PaymentMethod.UNKNOWN : cmd.paymentMethod(),
            cmd.merchantRaw(),
            cmd.notes(),
            List.copyOf(cmd.tags()),
            ExpenseStatus.CONFIRMED,
            ExpenseSource.MANUAL,
            null,
            now,
            now);
    Expense stored = expenses.insert(row);
    if (idempotencyKey != null && fingerprint != null) {
      boolean won =
          expenses.claimIdempotency(
              household.id(),
              idempotencyKey,
              fingerprint,
              stored.id(),
              now,
              now.minus(IDEMPOTENCY_TTL));
      if (!won) {
        throw new ExpenseException(
            Kind.CONFLICT,
            "Conflict",
            "A concurrent request used the same Idempotency-Key; retry",
            List.of());
      }
    }
    return new Created(stored, false);
  }

  /** Reads a live expense of the current household. */
  @Transactional(readOnly = true)
  public Expense get(UUID id) {
    return live(currentHousehold.get().id(), id);
  }

  /** Applies a partial update to a live expense. */
  @Transactional
  public Expense update(UUID id, PatchCommand patch) {
    Household household = currentHousehold.get();
    Expense current = live(household.id(), id);
    if (patch.amount() != null) {
      validateMoney(patch.amount(), household);
    }
    Instant now = clock.instant();
    if (patch.occurredAt() != null) {
      validateOccurredAt(patch.occurredAt(), now);
    }
    List<String> tags = patch.tags() == null ? current.tags() : List.copyOf(patch.tags());
    String notes = patch.notes() == null ? current.notes() : patch.notes();
    String merchantRaw = patch.merchantRaw() == null ? current.merchantRaw() : patch.merchantRaw();
    validateText(notes, merchantRaw, tags);
    validateReferences(
        household,
        patch.categoryId() == null || patch.categoryId().equals(current.categoryId())
            ? null
            : patch.categoryId(),
        patch.paidByMemberId(),
        patch.paymentAccountId());
    Expense next =
        new Expense(
            current.id(),
            current.householdId(),
            patch.type() == null ? current.type() : patch.type(),
            patch.amount() == null ? current.amount() : patch.amount(),
            patch.occurredAt() == null ? current.occurredAt() : patch.occurredAt(),
            patch.categoryId() == null ? current.categoryId() : patch.categoryId(),
            patch.paymentAccountId() == null
                ? current.paymentAccountId()
                : patch.paymentAccountId(),
            patch.paidByMemberId() == null ? current.paidByMemberId() : patch.paidByMemberId(),
            patch.paymentMethod() == null ? current.paymentMethod() : patch.paymentMethod(),
            merchantRaw,
            notes,
            tags,
            patch.status() == null ? current.status() : patch.status(),
            current.source(),
            null,
            current.createdAt(),
            now);
    return expenses.update(next);
  }

  /** Soft-deletes a live expense. */
  @Transactional
  public void delete(UUID id) {
    UUID household = currentHousehold.get().id();
    if (!expenses.softDelete(household, id, clock.instant())) {
      throw ExpenseException.notFound("Expense");
    }
  }

  /** Lists a filtered, sorted, keyset-paginated page. */
  @Transactional(readOnly = true)
  public Page list(ListQuery q) {
    Household household = currentHousehold.get();
    ExpenseSort sort =
        ExpenseSort.parse(q.sort())
            .orElseThrow(
                () ->
                    new ExpenseException(
                        Kind.BAD_REQUEST,
                        "Bad Request",
                        "Validation failed",
                        List.of(new Issue("sort", "is not a supported sort"))));
    Cursor after = null;
    if (q.cursor() != null) {
      after =
          Cursor.decode(q.cursor(), sort)
              .orElseThrow(
                  () ->
                      new ExpenseException(
                          Kind.BAD_REQUEST,
                          "Bad Request",
                          "Validation failed",
                          List.of(new Issue("cursor", "is not valid for this query"))));
    }
    if (q.from() != null && q.to() != null && q.from().isAfter(q.to())) {
      throw new ExpenseException(
          Kind.BAD_REQUEST,
          "Bad Request",
          "Validation failed",
          List.of(new Issue("from", "must not be after to")));
    }
    ExpenseFilter filter =
        new ExpenseFilter(
            household.id(),
            q.from() == null ? null : BusinessCalendar.startOfDay(q.from()),
            q.to() == null ? null : BusinessCalendar.endOfDayExclusive(q.to()),
            q.memberId(),
            q.categoryId(),
            q.merchantFilter(),
            q.accountId(),
            q.source(),
            q.status(),
            q.type(),
            q.minAmountMinor(),
            q.maxAmountMinor(),
            q.text() == null || q.text().isBlank() ? null : q.text().trim(),
            q.includeDeleted(),
            sort,
            after,
            q.limit());
    List<Expense> rows = new ArrayList<>(expenses.list(filter));
    String next = null;
    if (rows.size() > q.limit()) {
      rows = new ArrayList<>(rows.subList(0, q.limit()));
      Expense last = rows.get(rows.size() - 1);
      next = cursorOf(sort, last).encode();
    }
    long total = expenses.totalMinor(filter);
    return new Page(rows, next, Money.of(total, household.baseCurrency()));
  }

  private static Cursor cursorOf(ExpenseSort sort, Expense row) {
    Instant instant =
        switch (sort) {
          case CREATED_AT_ASC, CREATED_AT_DESC -> row.createdAt();
          default -> row.occurredAt();
        };
    return new Cursor(sort, row.amount().amountMinor(), instant, row.id());
  }

  private Expense live(UUID householdId, UUID id) {
    return expenses
        .find(householdId, id)
        .filter(e -> e.deletedAt() == null)
        .orElseThrow(() -> ExpenseException.notFound("Expense"));
  }

  private static void validateMoney(Money amount, Household household) {
    if (amount.amountMinor() <= 0 || amount.amountMinor() > MAX_AMOUNT_MINOR) {
      throw ExpenseException.invalid(
          "amount.amountMinor", "must be between 1 and " + MAX_AMOUNT_MINOR);
    }
    try {
      Currency.getInstance(amount.currency());
    } catch (IllegalArgumentException e) {
      throw ExpenseException.invalid("amount.currency", "is not a known ISO-4217 code");
    }
    if (!amount.currency().equals(household.baseCurrency())) {
      throw new ExpenseException(
          Kind.UNPROCESSABLE,
          "Foreign currency needs FX",
          "Only " + household.baseCurrency() + " is supported until FX arrives",
          List.of(new Issue("amount.currency", "must be " + household.baseCurrency())));
    }
  }

  private static void validateOccurredAt(Instant occurredAt, Instant now) {
    if (occurredAt.isAfter(now.plus(MAX_FUTURE))) {
      throw ExpenseException.invalid("occurredAt", "must not be more than 1 day in the future");
    }
    if (occurredAt.isBefore(MIN_OCCURRED_AT)) {
      throw ExpenseException.invalid("occurredAt", "must be on or after 2000-01-01");
    }
  }

  /**
   * Lengths count Unicode code points, as the database does; NUL is rejected (Postgres cannot store
   * it).
   */
  private static void validateText(
      @Nullable String notes, @Nullable String merchantRaw, List<String> tags) {
    checkText("notes", notes, MAX_NOTES);
    checkText("merchantRaw", merchantRaw, MAX_MERCHANT);
    if (tags.size() > MAX_TAGS) {
      throw ExpenseException.invalid("tags", "must have at most " + MAX_TAGS + " entries");
    }
    for (String tag : tags) {
      checkText("tags", tag, MAX_TAG_LENGTH);
    }
  }

  private static void checkText(String field, @Nullable String value, int maxCodePoints) {
    if (value == null) {
      return;
    }
    if (value.indexOf('\u0000') >= 0) {
      throw ExpenseException.invalid(field, "must not contain the NUL character");
    }
    if (value.codePointCount(0, value.length()) > maxCodePoints) {
      throw ExpenseException.invalid(field, "must be at most " + maxCodePoints + " characters");
    }
  }

  private void validateReferences(
      Household household,
      @Nullable UUID categoryId,
      @Nullable UUID paidByMemberId,
      @Nullable UUID paymentAccountId) {
    if (categoryId != null) {
      ReferenceData.CategoryView category =
          reference
              .category(household.id(), categoryId)
              .orElseThrow(() -> ExpenseException.notFound("Category"));
      if (category.archived()) {
        throw ExpenseException.invalid("categoryId", "category is archived");
      }
    }
    if (paidByMemberId != null && !reference.memberExists(household.id(), paidByMemberId)) {
      throw ExpenseException.notFound("Member");
    }
    if (paymentAccountId != null && !reference.accountExists(household.id(), paymentAccountId)) {
      throw ExpenseException.notFound("Payment account");
    }
  }
}
