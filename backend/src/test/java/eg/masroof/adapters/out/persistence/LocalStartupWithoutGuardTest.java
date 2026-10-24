package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eg.masroof.MasroofApplication;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * REQ-OPS-143 regression guard: without profile prod the guard never blocks start-up; the failure
 * comes from the datasource connection attempt (nothing listens on port 1, no Docker). Deliberately
 * not a {@code @SpringBootTest}: the application is started by hand inside {@code assertThrows}.
 */
class LocalStartupWithoutGuardTest {

  @Test
  @Tag("REQ-OPS-143")
  @DisplayName("REQ-OPS-143 local start-up fails on the datasource connection, not on the guard")
  void localStartupFailsOnConnectionNotOnGuard() {
    Throwable failure =
        assertThrows(
            Throwable.class,
            () ->
                new SpringApplicationBuilder(MasroofApplication.class)
                    .run("--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/x"));

    List<Throwable> chain = new ArrayList<>();
    for (Throwable t = failure; t != null && !chain.contains(t); t = t.getCause()) {
      chain.add(t);
    }

    assertThat(chain)
        .as("cause chain reaches the datasource connection failure")
        .anyMatch(
            t ->
                t instanceof ConnectException
                    || "org.postgresql.util.PSQLException".equals(t.getClass().getName()));
    assertThat(chain)
        .as("no guard message")
        .noneMatch(
            t -> {
              String m = String.valueOf(t.getMessage());
              return m.contains("spring.datasource.password") && m.contains("POSTGRES_PASSWORD");
            });
  }
}
