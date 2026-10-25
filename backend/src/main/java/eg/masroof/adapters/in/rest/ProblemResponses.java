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

  private static void write(
      HttpServletResponse response, int status, String title, String detail, String instance)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String json =
        "{\"type\":\"about:blank\",\"title\":\""
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
