package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.HealthApi;
import eg.masroof.adapters.in.rest.generated.model.Health;
import eg.masroof.application.health.HealthReport;
import eg.masroof.application.health.HealthService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /health (REQ-OPS-020) implementing the generated {@link HealthApi} (REQ-OPS-050). The method
 * re-declares the mapping without the generated {@code produces}, so Accept is ignored and the
 * preset Content-Type applies; the path still comes from the generated constant. Not final: the
 * generated interface is {@code @Validated}, so Spring proxies the class.
 */
@RestController
class HealthController implements HealthApi {

  private final HealthService service;

  HealthController(HealthService service) {
    this.service = service;
  }

  /** The database probe failed; rendered as the fixed 503 problem document. */
  private static final class DatabaseDownException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DatabaseDownException() {
      super("Database unavailable", null, false, false);
    }
  }

  @Override
  @GetMapping(HealthApi.PATH_GET_HEALTH)
  public ResponseEntity<Health> getHealth() {
    HealthReport report = service.check();
    if (!report.databaseUp()) {
      throw new DatabaseDownException();
    }
    Health body =
        new Health(
                Health.StatusEnum.UP,
                report.version(),
                report.gitSha(),
                report.buildTime(),
                report.time())
            .putChecksItem("db", Health.InnerEnum.UP);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }

  @ExceptionHandler(DatabaseDownException.class)
  ResponseEntity<ProblemDetail> databaseDown() {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable");
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Service Unavailable");
    problem.setInstance(URI.create(HealthApi.PATH_GET_HEALTH));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
