package eg.masroof.application.health;

import java.time.Instant;

/** Result of one health check. */
public record HealthReport(
    String version, String gitSha, Instant buildTime, Instant time, boolean databaseUp) {}
