package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import eg.masroof.application.health.DatabaseProbe;
import eg.masroof.application.health.HealthService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** REQ-OPS-020: GET /health through the MVC slice (the controller, the use case, a fake probe). */
@WebMvcTest(HealthController.class)
@Import({HealthService.class, HealthControllerTest.Fixture.class})
class HealthControllerTest {

  private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
  private static final Pattern GIT_SHA = Pattern.compile("^[0-9a-f]{7,40}$");
  private static final Pattern LEAK =
      Pattern.compile(
          "jdbc:|postgres(ql)?://|password|exception|hikari|:5432|\\.java:|\\tat ",
          Pattern.CASE_INSENSITIVE);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Object> UP_BODY =
      Map.of(
          "status", "UP",
          "version", "0.1.0",
          "gitSha", "1a2b3c4",
          "buildTime", "2026-10-23T10:00:00Z",
          "time", "2026-10-23T12:34:56Z",
          "checks", Map.of("db", "UP"));
  private static final Map<String, Object> PROBLEM_BODY =
      Map.of(
          "type", "about:blank",
          "title", "Service Unavailable",
          "status", 503,
          "detail", "Database unavailable",
          "instance", "/health");

  @TestConfiguration
  static class Fixture {
    static final AtomicBoolean DB_UP = new AtomicBoolean(true);

    @Bean
    BuildProperties buildProperties() {
      Properties p = new Properties();
      p.setProperty("group", "eg.masroof");
      p.setProperty("name", "masroof-backend");
      p.setProperty("version", "0.1.0");
      p.setProperty("time", "2026-10-23T10:00:00Z");
      p.setProperty("gitSha", "1a2b3c4");
      return new BuildProperties(p);
    }

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-10-23T12:34:56Z"), ZoneOffset.UTC);
    }

    @Bean
    DatabaseProbe databaseProbe() {
      return DB_UP::get;
    }
  }

  private final MockMvc mvc;

  HealthControllerTest(@Autowired MockMvc mvc) {
    this.mvc = mvc;
  }

  @BeforeEach
  void dbUp() {
    Fixture.DB_UP.set(true);
  }

  private static Map<String, Object> parse(MvcResult result) throws Exception {
    return JSON.readValue(
        result.getResponse().getContentAsString(), new TypeReference<Map<String, Object>>() {});
  }

  @Test
  @Tag("REQ-OPS-020")
  @DisplayName("REQ-OPS-020 200 application/json with exactly the six Health members")
  void healthShape() throws Exception {
    MvcResult result = mvc.perform(get("/health")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentType()).startsWith("application/json");
    Map<String, Object> body = parse(result);
    assertThat(body).isEqualTo(UP_BODY);
    assertThat((String) body.get("version")).matches(SEMVER);
    assertThat((String) body.get("gitSha")).matches(GIT_SHA);
    assertThat((String) body.get("buildTime")).endsWith("Z");
    assertThat((String) body.get("time")).endsWith("Z");
    assertThat(Instant.parse((String) body.get("buildTime")))
        .isBeforeOrEqualTo(Instant.parse((String) body.get("time")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"application/xml", "text/html", "*/*"})
  @Tag("REQ-OPS-020")
  @DisplayName("REQ-OPS-020 the Accept header is ignored: 200 application/json, never 406")
  void acceptIsIgnored(String accept) throws Exception {
    MvcResult result = mvc.perform(get("/health").header("Accept", accept)).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentType()).startsWith("application/json");
    assertThat(parse(result)).isEqualTo(UP_BODY);
  }

  @Test
  @Tag("REQ-OPS-020")
  @DisplayName("REQ-OPS-020 unknown query parameters are ignored and never echoed")
  void queryIsIgnored() throws Exception {
    String longValue = "a".repeat(2000);
    MvcResult result =
        mvc.perform(get("/health").param("verbose", "true").param("x", longValue)).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(parse(result)).isEqualTo(UP_BODY);
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("verbose")
        .doesNotContain(longValue);
  }

  @Test
  @Tag("REQ-OPS-020")
  @DisplayName("REQ-OPS-020 database down: 503 problem+json with the fixed body and no leaks")
  void databaseDownIsProblemJson() throws Exception {
    Fixture.DB_UP.set(false);

    MvcResult result = mvc.perform(get("/health")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
    assertThat(parse(result)).isEqualTo(PROBLEM_BODY);
    List<String> surface = new ArrayList<>();
    surface.add(result.getResponse().getContentAsString());
    for (String name : result.getResponse().getHeaderNames()) {
      surface.add(name);
      surface.addAll(result.getResponse().getHeaders(name));
    }
    assertThat(surface).noneSatisfy(s -> assertThat(LEAK.matcher(s).find()).isTrue());
  }
}
