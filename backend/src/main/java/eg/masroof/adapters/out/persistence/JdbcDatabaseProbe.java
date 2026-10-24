package eg.masroof.adapters.out.persistence;

import eg.masroof.application.health.DatabaseProbe;
import java.sql.Connection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Probes Postgres with a bounded (2 s) connection validity check. */
@Component
final class JdbcDatabaseProbe implements DatabaseProbe, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcDatabaseProbe.class);

  private final DataSource dataSource;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  JdbcDatabaseProbe(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public boolean isUp() {
    Future<Boolean> future =
        executor.submit(
            () -> {
              try (Connection connection = dataSource.getConnection()) {
                return connection.isValid(1);
              }
            });
    try {
      return future.get(2, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      return fail(e);
    } catch (ExecutionException | RuntimeException e) {
      return fail(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fail(e);
    }
  }

  // pgjdbc messages contain host:port, so only the class name is logged.
  private static boolean fail(Exception e) {
    LOG.warn("database probe failed: {}", e.getClass().getSimpleName());
    return false;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
