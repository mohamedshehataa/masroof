package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.ApiProblemException.FieldProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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
    return ResponseEntity.status(e.status()).body(problem);
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
    LOG.error("Unhandled error on {}", request.getRequestURI(), e);
    return handleApi(
        new ApiProblemException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"), request);
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
    return dot < 0 ? path : path.substring(dot + 1);
  }
}
