package eg.masroof.adapters.in.rest;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An error the API reports as RFC 9457 {@code application/problem+json}. */
public class ApiProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** One field-level validation error. Never carries the rejected value. */
  public record FieldProblem(String field, String message) {}

  private final HttpStatus status;
  private final transient List<FieldProblem> errors;

  /** A problem without field errors. */
  public ApiProblemException(HttpStatus status, String detail) {
    this(status, detail, List.of());
  }

  /** A problem with field-level errors. */
  public ApiProblemException(HttpStatus status, String detail, List<FieldProblem> errors) {
    super(detail);
    this.status = status;
    this.errors = List.copyOf(errors);
  }

  /** The HTTP status. */
  public HttpStatus status() {
    return status;
  }

  /** Field-level errors, possibly empty. */
  public List<FieldProblem> errors() {
    return errors;
  }
}
