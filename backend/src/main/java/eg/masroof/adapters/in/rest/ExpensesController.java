package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.ExpensesApi;
import eg.masroof.adapters.in.rest.generated.model.Expense;
import eg.masroof.adapters.in.rest.generated.model.ExpenseCreate;
import eg.masroof.adapters.in.rest.generated.model.ExpensePage;
import eg.masroof.adapters.in.rest.generated.model.ExpensePatch;
import eg.masroof.adapters.in.rest.generated.model.ExpenseStatus;
import eg.masroof.adapters.in.rest.generated.model.MessageSource;
import eg.masroof.adapters.in.rest.generated.model.TransactionType;
import eg.masroof.application.expense.ExpenseService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** /api/expenses (REQ-EXP-001, -002, -003, -004, -020, -021, -023). */
@RestController
class ExpensesController implements ExpensesApi {

  private final ExpenseService service;
  private final JsonMapper json;

  ExpensesController(ExpenseService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @Override
  public ResponseEntity<Expense> createExpense(
      ExpenseCreate expenseCreate, @Nullable String idempotencyKey) {
    ExpenseMapper.requireSupported(expenseCreate);
    String fingerprint = idempotencyKey == null ? null : fingerprint(expenseCreate);
    ExpenseService.Created created =
        service.create(ExpenseMapper.toCommand(expenseCreate), idempotencyKey, fingerprint);
    Expense body = ExpenseMapper.toDto(created.expense());
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/expenses/" + body.getId()))
        .body(body);
  }

  @Override
  public ResponseEntity<Expense> getExpense(UUID expenseId) {
    return ResponseEntity.ok(ExpenseMapper.toDto(service.get(expenseId)));
  }

  @Override
  public ResponseEntity<Expense> updateExpense(UUID expenseId, ExpensePatch expensePatch) {
    ExpenseMapper.requireSupported(expensePatch);
    return ResponseEntity.ok(
        ExpenseMapper.toDto(service.update(expenseId, ExpenseMapper.toCommand(expensePatch))));
  }

  @Override
  public ResponseEntity<Void> deleteExpense(UUID expenseId) {
    service.delete(expenseId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<ExpensePage> listExpenses(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable UUID memberId,
      @Nullable UUID categoryId,
      @Nullable UUID merchantId,
      @Nullable UUID accountId,
      @Nullable MessageSource source,
      @Nullable ExpenseStatus status,
      @Nullable TransactionType type,
      @Nullable Long minAmountMinor,
      @Nullable Long maxAmountMinor,
      @Nullable String q,
      Boolean includeDeleted,
      String sort,
      @Nullable String cursor,
      Integer limit) {
    ExpenseService.ListQuery query =
        new ExpenseService.ListQuery(
            from,
            to,
            memberId,
            categoryId,
            merchantId != null,
            accountId,
            ExpenseMapper.source(source),
            ExpenseMapper.status(status),
            ExpenseMapper.type(type),
            minAmountMinor,
            maxAmountMinor,
            q,
            includeDeleted,
            sort,
            cursor,
            limit);
    return ResponseEntity.ok(ExpenseMapper.toDto(service.list(query)));
  }

  private String fingerprint(ExpenseCreate body) {
    try {
      byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
