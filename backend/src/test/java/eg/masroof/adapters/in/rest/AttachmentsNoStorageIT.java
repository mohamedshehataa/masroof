package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Without S3 settings the app starts, attachments answer 503 and the rest of the API works. */
@SpringBootTest(properties = {"masroof.dev.single-household=true", "masroof.s3.endpoint="})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttachmentsNoStorageIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeAll
  void seed() {
    jdbc.update(
        "insert into household (id, name, created_at) values (gen_random_uuid(), 'NoS3', now() - interval '1 day')");
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName(
      "REQ-RCPT-023 unconfigured storage: upload and download are 503, nothing is stored, the API works")
  void unconfigured() throws Exception {
    MockHttpServletResponse created =
        mvc.perform(
                post("/api/expenses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"type":"EXPENSE","amount":{"amountMinor":100,"currency":"EGP"},\
                        "occurredAt":"2026-10-20T10:00:00Z"}"""))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).isEqualTo(201);
    String id = JSON.readTree(created.getContentAsString()).get("id").asString();

    MockHttpServletResponse up =
        mvc.perform(
                multipart("/api/expenses/" + id + "/attachments")
                    .file(new MockMultipartFile("file", "r.png", "image/png", TestImages.png())))
            .andReturn()
            .getResponse();
    assertThat(up.getStatus()).isEqualTo(503);
    assertThat(up.getContentType()).startsWith("application/problem+json");
    assertThat(jdbc.queryForObject("select count(*) from expense_attachment", Integer.class))
        .isZero();

    assertThat(
            mvc.perform(get("/api/expenses/" + id + "/attachments"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(
            mvc.perform(get("/api/expenses/" + id + "/attachments/" + UUID.randomUUID()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            mvc.perform(delete("/api/expenses/" + id + "/attachments/" + UUID.randomUUID()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(mvc.perform(get("/api/expenses/" + id)).andReturn().getResponse().getStatus())
        .isEqualTo(200);
  }
}
