package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.ApiProblemException.FieldProblem;
import eg.masroof.application.auth.AuthException;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.household.HouseholdNotConfiguredException;
import eg.masroof.application.household.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/** Turns every failure into an RFC 9457 problem; never echoes input values or stack traces. */
@RestControllerAdvice
class ProblemAdvice extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

  @ExceptionHandler(ApiProblemException.class)
  ResponseEntity<Object> handleApi(ApiProblemException e, HttpServletRequest request) {
    ProblemDetail problem =
        problem(e.status(), String.valueOf(e.getMessage()), request.getRequestURI());
    if (!e.errors().isEmpty()) {
      problem.setProperty("errors", e.errors());
    }
    if (e.status().is5xxServerError()) {
      LOG.error(
          "Server error on {} traceId={}",
          request.getRequestURI(),
          Optional.ofNullable(problem.getProperties()).map(m -> m.get("traceId")).orElse("-"));
    }
    return ResponseEntity.status(e.status()).body(problem);
  }

  @ExceptionHandler(ExpenseException.class)
  ResponseEntity<Object> handleExpense(ExpenseException e, HttpServletRequest request) {
    HttpStatus status =
        switch (e.kind()) {
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case CONFLICT -> HttpStatus.CONFLICT;
          case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    ProblemDetail problem =
        problem(status, String.valueOf(e.getMessage()), request.getRequestURI());
    problem.setTitle(e.title());
    if (!e.issues().isEmpty()) {
      problem.setProperty(
          "errors",
          e.issues().stream().map(i -> new FieldProblem(i.field(), i.message())).toList());
    }
    return ResponseEntity.status(status).body(problem);
  }

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<Object> handleUnauthenticated(
      UnauthenticatedException e, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNAUTHORIZED, "Authentication is required", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .body(problem);
  }

  @ExceptionHandler(AuthException.class)
  ResponseEntity<Object> handleAuth(AuthException e, HttpServletRequest request) {
    boolean limited = e.kind() == AuthException.Kind.RATE_LIMITED;
    HttpStatus status = limited ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED;
    ProblemDetail problem =
        problem(
            status,
            limited ? "Too many failed attempts; try again later" : "Authentication failed",
            request.getRequestURI());
    problem.setTitle(e.title());
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    Duration wait = e.retryAfter();
    if (limited && wait != null) {
      long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
    if (!limited) {
      response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
    return response.body(problem);
  }

  @ExceptionHandler(HouseholdNotConfiguredException.class)
  ResponseEntity<Object> handleNoHousehold(
      HouseholdNotConfiguredException e, HttpServletRequest request) {
    return handleApi(
        new ApiProblemException(
            HttpStatus.SERVICE_UNAVAILABLE, "No household is set up yet; seed the demo data"),
        request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> handleConstraint(
      ConstraintViolationException e, HttpServletRequest request) {
    List<FieldProblem> errors =
        e.getConstraintViolations().stream()
            .map(v -> new FieldProblem(lastSegment(v.getPropertyPath().toString()), v.getMessage()))
            .sorted(Comparator.comparing(FieldProblem::field))
            .toList();
    return handleApi(
        new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed", errors), request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Object> handleTypeMismatch(
      MethodArgumentTypeMismatchException e, HttpServletRequest request) {
    return handleApi(
        new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "Validation failed",
            List.of(new FieldProblem(e.getName(), "has an invalid format"))),
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception e, HttpServletRequest request) {
    // Database messages embed the failing row's values, so log only the type and the first line
    // (constraint name); never the stack trace or the Detail lines.
    Throwable root = e instanceof DataAccessException dae ? dae.getMostSpecificCause() : e;
    String firstLine = String.valueOf(root.getMessage()).lines().findFirst().orElse("");
    LOG.error(
        "Unhandled {} on {}: {}",
        e.getClass().getSimpleName(),
        request.getRequestURI(),
        e instanceof DataAccessException ? firstLine : "(message withheld)");
    return handleApi(
        new ApiProblemException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"), request);
  }

  /**
   * A well-formed body with an invalid currency code is semantically wrong (422), not malformed.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String field = null;
    boolean badCurrency = false;
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof JacksonException je && field == null && !je.getPath().isEmpty()) {
        field = path(je);
      }
      if (t instanceof IllegalArgumentException
          && String.valueOf(t.getMessage()).startsWith("currency must be")) {
        badCurrency = true;
      }
    }
    if (badCurrency) {
      ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed", null);
      problem.setProperty(
          "errors",
          List.of(
              new FieldProblem(
                  field == null || field.isEmpty()
                      ? "amount.currency"
                      : field.endsWith(".currency") ? field : field + ".currency",
                  "is not an ISO-4217 code")));
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }
    boolean named = field != null;
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            named ? "Validation failed" : "Request body is not valid JSON",
            null);
    if (field != null) {
      problem.setProperty(
          "errors", List.of(new FieldProblem(field, "has a missing or invalid value or type")));
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
  }

  /** Dotted field path of a Jackson mapping failure, for example {@code amount.amountMinor}. */
  private static String path(JacksonException e) {
    StringBuilder sb = new StringBuilder();
    for (JacksonException.Reference ref : e.getPath()) {
      if (ref.getPropertyName() != null) {
        if (!sb.isEmpty()) {
          sb.append('.');
        }
        sb.append(ref.getPropertyName());
      } else if (ref.getIndex() >= 0) {
        sb.append('[').append(ref.getIndex()).append(']');
      }
    }
    return sb.toString();
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldProblem> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new FieldProblem(f.getField(), String.valueOf(f.getDefaultMessage())))
            .sorted(Comparator.comparing(FieldProblem::field))
            .toList();
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", null);
    problem.setProperty("errors", errors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
  }

  private static ProblemDetail problem(HttpStatus status, String detail, @Nullable String path) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("about:blank"));
    if (!"/health".equals(path)) { // /health keeps its fixed body (REQ-OPS-020)
      problem.setProperty("traceId", UUID.randomUUID().toString());
    }
    problem.setTitle(status.getReasonPhrase());
    if (path != null) {
      try {
        problem.setInstance(URI.create(path));
      } catch (IllegalArgumentException ignored) {
        // an instance is optional; skip a path that is not a valid URI reference
      }
    }
    return problem;
  }

  private static String lastSegment(String path) {
    int dot = path.lastIndexOf('.');
    String name = dot < 0 ? path : path.substring(dot + 1);
    return "idempotencyKey".equals(name) ? "Idempotency-Key" : name;
  }

  /** Framework-built problems (404, 415, 405 ...) get an explicit {@code type} and instance too. */
  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail pd) {
      // Jackson omits the default type unless it is set explicitly
      pd.setType(URI.create("about:blank"));
      pd.setProperty("traceId", UUID.randomUUID().toString());
      if (pd.getInstance() == null && request instanceof ServletWebRequest swr) {
        try {
          pd.setInstance(URI.create(swr.getRequest().getRequestURI()));
        } catch (IllegalArgumentException ignored) {
          // optional
        }
      }
    }
    return response;
  }
}
