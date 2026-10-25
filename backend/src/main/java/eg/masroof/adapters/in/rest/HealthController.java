package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.HealthApi;
import eg.masroof.adapters.in.rest.generated.model.Health;
import eg.masroof.adapters.in.rest.generated.model.Health.InnerEnum;
import eg.masroof.adapters.in.rest.generated.model.Health.StatusEnum;
import eg.masroof.application.health.HealthReport;
import eg.masroof.application.health.HealthService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /health (REQ-OPS-020). The preset Content-Type makes Spring skip Accept negotiation. */
@RestController
class HealthController implements HealthApi {

  private final HealthService service;

  HealthController(HealthService service) {
    this.service = service;
  }

  // Re-declared without the interface's `produces`: /health ignores Accept and never answers 406.
  @Override
  @GetMapping("/health")
  public ResponseEntity<Health> getHealth() {
    HealthReport report = service.check();
    if (!report.databaseUp()) {
      throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable");
    }
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new Health()
                .status(StatusEnum.UP)
                .version(report.version())
                .gitSha(report.gitSha())
                .buildTime(report.buildTime())
                .time(report.time())
                .checks(Map.of("db", InnerEnum.UP)));
  }
}
