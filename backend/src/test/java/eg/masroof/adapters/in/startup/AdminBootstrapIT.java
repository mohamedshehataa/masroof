package eg.masroof.adapters.in.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** First-user bootstrap on an empty database. */
@SpringBootTest(
    properties = {
      "masroof.bootstrap.admin-email=Owner@Example.com",
      "masroof.bootstrap.admin-password=a long bootstrap phrase"
    })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class AdminBootstrapIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AdminBootstrapRunner runner;

  @Test
  @Tag("REQ-SEC-001")
  @DisplayName(
      "REQ-SEC-001 an empty database gets one admin in a new household; a rerun adds nothing")
  void bootstrapsOnce() throws Exception {
    assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from household", Integer.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select m.role from member m join app_user u on u.member_id = m.id", String.class))
        .isEqualTo("ADMIN");
    assertThat(jdbc.queryForObject("select email from app_user", String.class))
        .isEqualTo("owner@example.com");
    assertThat(jdbc.queryForObject("select password_hash from app_user", String.class))
        .startsWith("$2b$12$")
        .doesNotContain("bootstrap");

    runner.run(new org.springframework.boot.DefaultApplicationArguments());
    assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);

    assertThat(
            mvc.perform(
                    post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"email\":\"OWNER@example.com\",\"password\":\"a long bootstrap phrase\"}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }
}
