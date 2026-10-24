package eg.masroof.adapters.in.rest;

import eg.masroof.application.health.HealthReport;
import eg.masroof.application.health.HealthService;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /health (REQ-OPS-020). The preset Content-Type makes Spring skip Accept negotiation. */
@RestController
final class HealthController {

  private final HealthService service;

  HealthController(HealthService service) {
    this.service = service;
  }

  record HealthResponse(
      String status,
      String version,
      String gitSha,
      String buildTime,
      String time,
      Map<String, String> checks) {}

  @GetMapping("/health")
  ResponseEntity<Object> getHealth() {
    HealthReport report = service.check();
    if (!report.databaseUp()) {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable");
      problem.setType(URI.create("about:blank"));
      problem.setTitle("Service Unavailable");
      problem.setInstance(URI.create("/health"));
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem);
    }
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new HealthResponse(
                "UP",
                report.version(),
                report.gitSha(),
                report.buildTime().toString(),
                report.time().toString(),
                Map.of("db", "UP")));
  }
}
