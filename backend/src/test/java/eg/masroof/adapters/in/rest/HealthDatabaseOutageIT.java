package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** REQ-OPS-020: real build info, 503 within 3 s when Postgres stops, recovery without restart. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("integration")
class HealthDatabaseOutageIT {

  private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
  private static final Pattern GIT_SHA = Pattern.compile("^[0-9a-f]{7,40}$");
  private static final Pattern LEAK =
      Pattern.compile(
          "jdbc:|postgres(ql)?://|password|exception|hikari|:5432|\\.java:|\\tat ",
          Pattern.CASE_INSENSITIVE);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int HOST_PORT = freePort();

  // postgres:16.15-alpine3.24 by digest; fixed host port so stop/start keeps the JDBC URL valid.
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36")
          .withCreateContainerCmdModifier(
              c ->
                  c.getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindPort(HOST_PORT), new ExposedPort(5432))));

  @LocalServerPort int port;
  @Autowired ApplicationContext context;

  private final HttpClient http = HttpClient.newBuilder().build();

  private static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<String> getHealth() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, Object> parse(String body) {
    return JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
  }

  @Test
  @Tag("REQ-OPS-020")
  @DisplayName("REQ-OPS-020 healthy, then 503 within 3 s when Postgres stops, then recovers")
  void outageAndRecovery() throws Exception {
    long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
    long contextStart = context.getStartupDate();

    HttpResponse<String> up = getHealth();
    assertThat(up.statusCode()).isEqualTo(200);
    Map<String, Object> body = parse(up.body());
    assertThat(body.get("status")).isEqualTo("UP");
    assertThat((String) body.get("version")).matches(SEMVER);
    assertThat((String) body.get("gitSha")).matches(GIT_SHA);
    assertThat(body.get("checks")).isEqualTo(Map.of("db", "UP"));

    DockerClientFactory.instance().client().stopContainerCmd(POSTGRES.getContainerId()).exec();
    long t0 = System.nanoTime();
    HttpResponse<String> down = getHealth();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);

    assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    assertThat(down.statusCode()).isEqualTo(503);
    assertThat(down.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(parse(down.body()))
        .isEqualTo(
            Map.of(
                "type", "about:blank",
                "title", "Service Unavailable",
                "status", 503,
                "detail", "Database unavailable",
                "instance", "/health"));
    List<String> surface = new ArrayList<>();
    surface.add(down.body());
    down.headers()
        .map()
        .forEach(
            (k, v) -> {
              surface.add(k);
              surface.addAll(v);
            });
    assertThat(surface).noneSatisfy(s -> assertThat(LEAK.matcher(s).find()).isTrue());

    DockerClientFactory.instance().client().startContainerCmd(POSTGRES.getContainerId()).exec();
    Instant deadline = Instant.now().plusSeconds(30);
    HttpResponse<String> recovered = getHealth();
    while (recovered.statusCode() != 200 && Instant.now().isBefore(deadline)) {
      Thread.sleep(500);
      recovered = getHealth();
    }
    assertThat(recovered.statusCode())
        .as("health within 30 s after Postgres restarts")
        .isEqualTo(200);
    assertThat(parse(recovered.body()).get("checks")).isEqualTo(Map.of("db", "UP"));
    assertThat(ManagementFactory.getRuntimeMXBean().getStartTime()).isEqualTo(jvmStart);
    assertThat(context.getStartupDate()).isEqualTo(contextStart);
  }
}
