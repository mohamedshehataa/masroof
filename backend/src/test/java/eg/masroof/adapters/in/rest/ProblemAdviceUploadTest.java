package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.application.attachment.AttachmentException;
import eg.masroof.application.attachment.AttachmentException.Kind;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Upload failures become problem+json with the status the contract declares. */
class ProblemAdviceUploadTest {

  private final ProblemAdvice advice = new ProblemAdvice();
  private final MockHttpServletRequest request =
      new MockHttpServletRequest("POST", "/api/expenses/x/attachments");

  @Test
  @Tag("REQ-RCPT-021")
  @DisplayName("REQ-RCPT-021 the container's multipart limit becomes a 413 problem")
  void containerLimit() {
    ResponseEntity<Object> response =
        Objects.requireNonNull(
            advice.handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(8L * 1024 * 1024),
                new HttpHeaders(),
                HttpStatus.CONTENT_TOO_LARGE,
                new ServletWebRequest(request)));
    assertThat(response.getStatusCode().value()).isEqualTo(413);
    ProblemDetail body = (ProblemDetail) Objects.requireNonNull(response.getBody());
    assertThat(Objects.requireNonNull(body.getType()).toString())
        .isEqualTo("https://masroof.app/problems/payload-too-large");
    assertThat(body.getProperties()).containsKey("traceId");
  }

  @Test
  @Tag("REQ-RCPT-021")
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-021 each attachment failure kind maps to its status")
  void kinds() {
    assertThat(
            advice
                .handleAttachment(new AttachmentException(Kind.EMPTY, "e"), request)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            advice
                .handleAttachment(new AttachmentException(Kind.TOO_LARGE, "e"), request)
                .getStatusCode()
                .value())
        .isEqualTo(413);
    assertThat(
            advice
                .handleAttachment(new AttachmentException(Kind.UNSUPPORTED_TYPE, "e"), request)
                .getStatusCode()
                .value())
        .isEqualTo(415);
    assertThat(
            advice
                .handleAttachment(new AttachmentException(Kind.STORAGE_UNAVAILABLE, "e"), request)
                .getStatusCode()
                .value())
        .isEqualTo(503);
  }
}
