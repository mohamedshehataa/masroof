package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Without masroof.dev.single-household the household API is closed: 401 Problem+JSON, /health open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class NoAuthGateIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  @Autowired MockMvc mvc;

  @Test
  @Tag("REQ-SEC-012")
  @DisplayName("REQ-SEC-012 household endpoints answer 401 problem+json while no auth exists")
  void householdEndpointsAreClosed() throws Exception {
    for (String path : new String[] {"/api/expenses", "/api/categories", "/api/accounts"}) {
      MockHttpServletResponse r = mvc.perform(get(path)).andReturn().getResponse();
      assertThat(r.getStatus()).as(path).isEqualTo(401);
      assertThat(r.getContentType()).startsWith("application/problem+json");
      assertThat(r.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }
    MockHttpServletResponse created =
        mvc.perform(
                post("/api/expenses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"type\":\"EXPENSE\",\"amount\":{\"amountMinor\":5,\"currency\":\"EGP\"},"
                            + "\"occurredAt\":\"2026-10-22T11:05:00Z\"}"))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).isEqualTo(401);
  }

  @Test
  @Tag("REQ-SEC-012")
  @DisplayName("REQ-SEC-012 /health stays open")
  void healthIsOpen() throws Exception {
    assertThat(mvc.perform(get("/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
  }
}
