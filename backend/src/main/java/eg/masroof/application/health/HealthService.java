package eg.masroof.application.health;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/** Use case: report build information and database reachability (REQ-OPS-020). */
@Service
public class HealthService {

  private static final Pattern GIT_SHA = Pattern.compile("^[0-9a-f]{7,40}$");

  private final String version;
  private final String gitSha;
  private final Instant buildTime;
  private final DatabaseProbe database;
  private final Clock clock;

  HealthService(BuildProperties build, DatabaseProbe database, Clock clock) {
    this.version = require(build.getVersion(), "version");
    this.gitSha = require(build.get("gitSha"), "gitSha");
    if (!GIT_SHA.matcher(gitSha).matches()) {
      throw new IllegalStateException("build info gitSha must match ^[0-9a-f]{7,40}$");
    }
    Instant time = build.getTime();
    if (time == null) {
      throw new IllegalStateException("build info time is missing");
    }
    this.buildTime = time;
    this.database = database;
    this.clock = clock;
  }

  private static String require(@Nullable String value, String key) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("build info " + key + " is missing");
    }
    return value;
  }

  /**
   * Runs one check.
   *
   * @return the build information, the current time and the database state
   */
  public HealthReport check() {
    return new HealthReport(
        version,
        gitSha,
        buildTime,
        Instant.now(clock).truncatedTo(ChronoUnit.SECONDS),
        database.isUp());
  }
}
