package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.application.auth.AuthException;
import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.expense.ExpenseException.Kind;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("REQ-EXP-020")
class ProblemAdviceTest {

  private final ProblemAdvice advice = new ProblemAdvice();
  private final MockHttpServletRequest request =
      new MockHttpServletRequest("POST", "/api/expenses");

  private static ProblemDetail body(ResponseEntity<Object> response) {
    return java.util.Objects.requireNonNull((ProblemDetail) response.getBody());
  }

  private static String type(ResponseEntity<Object> response) {
    return java.util.Objects.requireNonNull(body(response).getType()).toString();
  }

  @Test
  @DisplayName("REQ-EXP-020 the concurrent Idempotency-Key 409 has its own stable type")
  void inProgress() {
    ExpenseException e =
        new ExpenseException(
            Kind.CONFLICT, "Conflict", "retry", List.of(), "idempotency-in-progress");
    ResponseEntity<Object> response = advice.handleExpense(e, request);
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(type(response)).isEqualTo("https://masroof.app/problems/idempotency-in-progress");
    assertThat(body(response).getTitle()).isEqualTo("Conflict");
  }

  @Test
  @DisplayName("REQ-EXP-020 kinds without a code get the generic type")
  void generic() {
    assertThat(type(advice.handleExpense(ExpenseException.notFound("Expense"), request)))
        .isEqualTo("https://masroof.app/problems/not-found");
    assertThat(type(advice.handleExpense(ExpenseException.invalid("f", "bad"), request)))
        .isEqualTo("https://masroof.app/problems/validation");
    assertThat(
            type(
                advice.handleExpense(
                    new ExpenseException(Kind.CONFLICT, "Conflict", "x", List.of()), request)))
        .isEqualTo("https://masroof.app/problems/conflict");
  }

  @Test
  @DisplayName("REQ-EXP-020 rate-limited and unauthorized auth failures have types")
  void auth() {
    assertThat(
            type(
                advice.handleAuth(
                    new AuthException(AuthException.Kind.RATE_LIMITED, Duration.ofSeconds(5)),
                    request)))
        .isEqualTo("https://masroof.app/problems/rate-limited");
    assertThat(
            type(
                advice.handleAuth(
                    new AuthException(AuthException.Kind.INVALID_CREDENTIALS), request)))
        .isEqualTo("https://masroof.app/problems/unauthorized");
  }
}
