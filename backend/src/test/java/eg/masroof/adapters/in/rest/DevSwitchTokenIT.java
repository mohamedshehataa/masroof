package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The dev single-household switch never turns a wrong token into access. */
@SpringBootTest(properties = "masroof.dev.single-household=true")
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class DevSwitchTokenIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  @Tag("REQ-SEC-012")
  @DisplayName(
      "REQ-SEC-012 with the switch on, no token uses the dev household but a bad token is 401")
  void badTokenStill401() throws Exception {
    jdbc.update("insert into household (id, name) values (?, 'Dev')", UUID.randomUUID());
    assertThat(mvc.perform(get("/api/categories")).andReturn().getResponse().getStatus())
        .isEqualTo(200);
    MockHttpServletResponse r =
        mvc.perform(get("/api/categories").header("Authorization", "Bearer garbage"))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(401);
    assertThat(r.getHeader("WWW-Authenticate")).startsWith("Bearer");
  }
}
