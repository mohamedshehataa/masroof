package eg.masroof.application.expense;

import java.util.List;

/** A business-rule failure the REST adapter maps to a problem+json response. */
public class ExpenseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The failure class (the adapter maps it to 400/404/409/422). */
  public enum Kind {
    BAD_REQUEST,
    NOT_FOUND,
    CONFLICT,
    UNPROCESSABLE
  }

  /** One offending field; never carries the value. */
  public record Issue(String field, String message) {}

  private final Kind kind;
  private final String title;
  private final transient List<Issue> issues;

  /** Creates an exception with a title and optional field issues. */
  public ExpenseException(Kind kind, String title, String detail, List<Issue> issues) {
    super(detail);
    this.kind = kind;
    this.title = title;
    this.issues = List.copyOf(issues);
  }

  /** Shorthand for a single-field 422. */
  public static ExpenseException invalid(String field, String message) {
    return new ExpenseException(
        Kind.UNPROCESSABLE, "Unprocessable Content", message, List.of(new Issue(field, message)));
  }

  /** Shorthand for a 404. */
  public static ExpenseException notFound(String what) {
    return new ExpenseException(Kind.NOT_FOUND, "Not Found", what + " not found", List.of());
  }

  /** The failure class. */
  public Kind kind() {
    return kind;
  }

  /** The problem title. */
  public String title() {
    return title;
  }

  /** Offending fields, possibly empty. */
  public List<Issue> issues() {
    return issues;
  }
}
