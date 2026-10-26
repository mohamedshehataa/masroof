package eg.masroof.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/** Writes RFC 9457 problems from servlet filters, which run before Spring MVC's advice. */
final class ProblemResponses {

  private ProblemResponses() {}

  /** 401 with {@code WWW-Authenticate: Bearer}. */
  static void unauthorized(
      HttpServletRequest request, HttpServletResponse response, @Nullable String error)
      throws IOException {
    response.setHeader(
        "WWW-Authenticate", error == null ? "Bearer" : "Bearer error=\"" + error + "\"");
    write(response, 401, "Unauthorized", "Authentication is required", request.getRequestURI());
  }

  /** 429 with {@code Retry-After} in whole seconds. */
  static void tooManyRequests(
      HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
      throws IOException {
    response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
    write(
        response,
        429,
        "Too Many Requests",
        "Too many requests; try again later",
        request.getRequestURI());
  }

  /** 413 for a body over the endpoint's limit. */
  static void payloadTooLarge(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(
        response,
        413,
        "Payload Too Large",
        "The request body is larger than this endpoint accepts",
        request.getRequestURI());
  }

  /** 422 for a structurally valid body the endpoint refuses (e.g. an unknown property). */
  static void unprocessable(HttpServletRequest request, HttpServletResponse response, String detail)
      throws IOException {
    write(response, 422, "Unprocessable Content", detail, request.getRequestURI());
  }

  /** 415 for a body in a charset the endpoint does not read. */
  static void unsupportedMediaType(
      HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
    write(response, 415, "Unsupported Media Type", detail, request.getRequestURI());
  }

  private static void write(
      HttpServletResponse response, int status, String title, String detail, String instance)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String json =
        "{\"type\":\""
            + ProblemTypes.forStatus(status)
            + "\",\"title\":\""
            + title
            + "\",\"status\":"
            + status
            + ",\"detail\":\""
            + detail
            + "\",\"instance\":\""
            + instance.replaceAll("[^A-Za-z0-9/_.~-]", "")
            + "\"}";
    response.getWriter().write(json);
  }
}
