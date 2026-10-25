package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.model.Expense;
import eg.masroof.adapters.in.rest.generated.model.ExpenseCreate;
import eg.masroof.adapters.in.rest.generated.model.ExpensePage;
import eg.masroof.adapters.in.rest.generated.model.ExpensePatch;
import eg.masroof.adapters.in.rest.generated.model.SplitMode;
import eg.masroof.adapters.in.rest.generated.model.TransactionType;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.expense.ExpenseService;
import eg.masroof.domain.expense.ExpenseSource;
import eg.masroof.domain.expense.ExpenseStatus;
import eg.masroof.domain.expense.ExpenseType;
import eg.masroof.domain.expense.PaymentMethod;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Maps between the generated transport model and application/domain types. */
final class ExpenseMapper {

  private ExpenseMapper() {}

  private static <E extends Enum<E>> E convert(Enum<?> from, Class<E> to) {
    return Enum.valueOf(to, from.name());
  }

  private static <E extends Enum<E>> @Nullable E convertOrNull(
      @Nullable Enum<?> from, Class<E> to) {
    return from == null ? null : convert(from, to);
  }

  /**
   * Rejects the contract fields the manual-entry slice does not store yet (never drops silently).
   */
  static void requireSupported(ExpenseCreate body) {
    if (body.getAmountOriginal() != null) {
      throw ExpenseException.invalid(
          "amountOriginal", "foreign-currency entry is not supported yet");
    }
    if (body.getMerchantId() != null) {
      throw ExpenseException.invalid("merchantId", "merchants are not supported yet");
    }
    if (body.getItems() != null && !body.getItems().isEmpty()) {
      throw ExpenseException.invalid("items", "line items are not supported yet");
    }
    if ((body.getSplits() != null && !body.getSplits().isEmpty())
        || (body.getSplitMode() != null && body.getSplitMode() != SplitMode.NONE)) {
      throw ExpenseException.invalid("splits", "splits are not supported yet");
    }
  }

  static void requireSupported(ExpensePatch body) {
    if (body.getMerchantId() != null) {
      throw ExpenseException.invalid("merchantId", "merchants are not supported yet");
    }
    if (body.getRecurringSeriesId() != null || Boolean.TRUE.equals(body.getIsRecurring())) {
      throw ExpenseException.invalid("isRecurring", "recurring series are not supported yet");
    }
    if (body.getItems() != null && !body.getItems().isEmpty()) {
      throw ExpenseException.invalid("items", "line items are not supported yet");
    }
  }

  /** Absent or null tags on create mean no tags; a null element is a client error, not a 500. */
  private static List<String> createTags(@Nullable List<String> tags) {
    List<String> checked = tags(tags);
    return checked == null ? List.of() : checked;
  }

  private static @Nullable List<String> tags(@Nullable List<String> tags) {
    if (tags != null && tags.stream().anyMatch(java.util.Objects::isNull)) {
      throw ExpenseException.invalid("tags", "must not contain null");
    }
    return tags;
  }

  static ExpenseService.CreateCommand toCommand(ExpenseCreate body) {
    return new ExpenseService.CreateCommand(
        convert(body.getType(), ExpenseType.class),
        body.getAmount(),
        body.getOccurredAt(),
        body.getCategoryId(),
        body.getPaidByMemberId(),
        body.getPaymentAccountId(),
        convertOrNull(body.getPaymentMethod(), PaymentMethod.class),
        body.getMerchantRaw(),
        body.getNotes(),
        createTags(body.getTags()));
  }

  static ExpenseService.PatchCommand toCommand(ExpensePatch body) {
    return new ExpenseService.PatchCommand(
        convertOrNull(body.getType(), ExpenseType.class),
        body.getAmount(),
        body.getOccurredAt(),
        body.getCategoryId(),
        body.getPaidByMemberId(),
        body.getPaymentAccountId(),
        convertOrNull(body.getPaymentMethod(), PaymentMethod.class),
        convertOrNull(body.getStatus(), ExpenseStatus.class),
        body.getMerchantRaw(),
        body.getNotes(),
        tags(body.getTags()));
  }

  static Expense toDto(eg.masroof.domain.expense.Expense e) {
    return new Expense()
        .id(e.id())
        .householdId(e.householdId())
        .type(convert(e.type(), TransactionType.class))
        .amount(e.amount())
        .occurredAt(e.occurredAt())
        .categoryId(e.categoryId())
        .paidByMemberId(e.paidByMemberId())
        .paymentAccountId(e.paymentAccountId())
        .paymentMethod(
            convert(
                e.paymentMethod(), eg.masroof.adapters.in.rest.generated.model.PaymentMethod.class))
        .merchantRaw(e.merchantRaw())
        .status(
            convert(e.status(), eg.masroof.adapters.in.rest.generated.model.ExpenseStatus.class))
        .confidence(BigDecimal.ONE)
        .notes(e.notes())
        .tags(e.tags())
        .isRecurring(false)
        .splitMode(SplitMode.NONE)
        .items(List.of())
        .sources(List.of())
        .splits(List.of())
        .createdAt(e.createdAt())
        .updatedAt(e.updatedAt())
        .deletedAt(e.deletedAt());
  }

  static ExpensePage toDto(ExpenseService.Page page) {
    ExpensePage dto =
        new ExpensePage()
            .items(page.items().stream().map(ExpenseMapper::toDto).toList())
            .totalAmount(page.total());
    String next = page.nextCursor();
    if (next != null) {
      dto.setNextCursor(next);
    }
    return dto;
  }

  static @Nullable ExpenseSource source(@Nullable Enum<?> source) {
    return convertOrNull(source, ExpenseSource.class);
  }

  static @Nullable ExpenseStatus status(@Nullable Enum<?> status) {
    return convertOrNull(status, ExpenseStatus.class);
  }

  static @Nullable ExpenseType type(@Nullable Enum<?> type) {
    return convertOrNull(type, ExpenseType.class);
  }
}
