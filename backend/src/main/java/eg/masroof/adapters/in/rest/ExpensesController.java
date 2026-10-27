package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.ExpensesApi;
import eg.masroof.adapters.in.rest.generated.model.Attachment;
import eg.masroof.adapters.in.rest.generated.model.AttachmentList;
import eg.masroof.adapters.in.rest.generated.model.Expense;
import eg.masroof.adapters.in.rest.generated.model.ExpenseCreate;
import eg.masroof.adapters.in.rest.generated.model.ExpensePage;
import eg.masroof.adapters.in.rest.generated.model.ExpensePatch;
import eg.masroof.adapters.in.rest.generated.model.ExpenseStatus;
import eg.masroof.adapters.in.rest.generated.model.MergeRequest;
import eg.masroof.adapters.in.rest.generated.model.MessageSource;
import eg.masroof.adapters.in.rest.generated.model.SplitRequest;
import eg.masroof.adapters.in.rest.generated.model.TransactionType;
import eg.masroof.application.attachment.AttachmentException;
import eg.masroof.application.attachment.AttachmentRepository;
import eg.masroof.application.attachment.AttachmentService;
import eg.masroof.application.expense.ExpenseService;
import eg.masroof.application.sms.ExpenseSourceService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** /api/expenses (REQ-EXP-001, -002, -003, -004, -020, -021, -023). */
@RestController
class ExpensesController implements ExpensesApi {

  private static final Pattern KEY = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

  private final ExpenseService service;
  private final ExpenseSourceService sources;
  private final JsonMapper json;
  private final AttachmentService attachments;

  ExpensesController(
      ExpenseService service,
      ExpenseSourceService sources,
      JsonMapper json,
      AttachmentService attachments) {
    this.service = service;
    this.sources = sources;
    this.json = json;
    this.attachments = attachments;
  }

  @Override
  public ResponseEntity<Expense> createExpense(
      ExpenseCreate expenseCreate, @Nullable String idempotencyKey) {
    if (idempotencyKey != null && !KEY.matcher(idempotencyKey).matches()) {
      throw new ApiProblemException(
          HttpStatus.BAD_REQUEST,
          "Validation failed",
          List.of(
              new ApiProblemException.FieldProblem(
                  "Idempotency-Key", "must be 1 to 128 printable ASCII characters")));
    }
    ExpenseMapper.requireSupported(expenseCreate);
    String fingerprint = idempotencyKey == null ? null : fingerprint(expenseCreate);
    ExpenseService.Created created =
        service.create(ExpenseMapper.toCommand(expenseCreate), idempotencyKey, fingerprint);
    Expense body =
        ExpenseMapper.toDto(
            created.expense(),
            service
                .itemsOf(List.of(created.expense().id()))
                .getOrDefault(created.expense().id(), List.of()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/expenses/" + body.getId()))
        .body(body);
  }

  @Override
  public ResponseEntity<Expense> getExpense(UUID expenseId) {
    eg.masroof.domain.expense.Expense found = service.get(expenseId);
    return ResponseEntity.ok(
        ExpenseMapper.toDto(
            found,
            service.itemsOf(List.of(found.id())).getOrDefault(found.id(), List.of()),
            sources.forExpenses(List.of(found.id())).get(found.id())));
  }

  @Override
  public ResponseEntity<Expense> updateExpense(UUID expenseId, ExpensePatch expensePatch) {
    ExpenseMapper.requireSupported(expensePatch);
    eg.masroof.domain.expense.Expense updated =
        service.update(expenseId, ExpenseMapper.toCommand(expensePatch));
    return ResponseEntity.ok(
        ExpenseMapper.toDto(
            updated,
            service.itemsOf(List.of(updated.id())).getOrDefault(updated.id(), List.of()),
            sources.forExpenses(List.of(updated.id())).get(updated.id())));
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
    ExpenseService.Page page = service.list(query);
    List<UUID> ids = page.items().stream().map(eg.masroof.domain.expense.Expense::id).toList();
    return ResponseEntity.ok(
        ExpenseMapper.toDto(page, sources.forExpenses(ids), service.itemsOf(ids)));
  }

  @Override
  public ResponseEntity<AttachmentList> listExpenseAttachments(UUID expenseId) {
    return ResponseEntity.ok(
        new AttachmentList()
            .items(attachments.list(expenseId).stream().map(ExpensesController::toDto).toList()));
  }

  @Override
  public ResponseEntity<Attachment> uploadExpenseAttachment(UUID expenseId, MultipartFile file) {
    if (file.getSize() > AttachmentService.MAX_BYTES) {
      throw new AttachmentException(AttachmentException.Kind.TOO_LARGE, "The file is too large");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ApiProblemException(HttpStatus.BAD_REQUEST, "The file could not be read");
    }
    Attachment body = toDto(attachments.upload(expenseId, bytes));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/expenses/" + expenseId + "/attachments/" + body.getId()))
        .body(body);
  }

  @Override
  public ResponseEntity<Resource> getExpenseAttachmentContent(UUID expenseId, UUID attachmentId) {
    AttachmentService.Content content = attachments.download(expenseId, attachmentId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.bytes().length)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(new ByteArrayResource(content.bytes()));
  }

  @Override
  public ResponseEntity<Void> deleteExpenseAttachment(UUID expenseId, UUID attachmentId) {
    attachments.delete(expenseId, attachmentId);
    return ResponseEntity.noContent().build();
  }

  private static Attachment toDto(AttachmentRepository.Row row) {
    return new Attachment()
        .id(row.id())
        .expenseId(row.expenseId())
        .contentType(Attachment.ContentTypeEnum.fromValue(row.contentType()))
        .sizeBytes(row.sizeBytes())
        .uploadedByMemberId(row.uploadedByMemberId())
        .createdAt(row.createdAt());
  }

  // Declared in the contract, built later (dedup, splits, restore): 501 Problem+JSON.
  @Override
  public ResponseEntity<Expense> mergeExpenses(UUID expenseId, MergeRequest mergeRequest) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<Expense> splitExpense(UUID expenseId, SplitRequest splitRequest) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<Expense> restoreExpense(UUID expenseId) {
    throw ApiProblemException.notImplemented();
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
