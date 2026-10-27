package eg.masroof.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import eg.masroof.application.attachment.AttachmentStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The S3 client against a tiny in-process server: wire shape, signing headers, failures. */
class S3AttachmentStoreTest {

  private HttpServer server;
  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> authHeaders = new CopyOnWriteArrayList<>();
  private volatile boolean bucketExists;
  private volatile int failWith;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        ex -> {
          String path = ex.getRequestURI().getRawPath();
          String method = ex.getRequestMethod();
          requests.add(method + " " + path);
          authHeaders.add(
              ex.getRequestHeaders().getFirst("Authorization")
                  + "|"
                  + ex.getRequestHeaders().getFirst("x-amz-content-sha256"));
          byte[] body = ex.getRequestBody().readAllBytes();
          int status = 200;
          byte[] out = new byte[0];
          if (failWith != 0) {
            status = failWith;
          } else if (path.equals("/media")) {
            if (method.equals("PUT")) {
              bucketExists = true;
            } else if (!bucketExists) {
              status = 404;
            }
          } else if (method.equals("PUT")) {
            objects.put(path, body);
          } else if (method.equals("GET")) {
            byte[] found = objects.get(path);
            status = found == null ? 404 : 200;
            out = found == null ? out : found;
          } else if (method.equals("DELETE")) {
            objects.remove(path);
            status = 204;
          }
          ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
          if (out.length > 0) {
            ex.getResponseBody().write(out);
          }
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private S3AttachmentStore store(String endpoint, String key) {
    return new S3AttachmentStore(endpoint, key, "secret", "media", "us-east-1", Clock.systemUTC());
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 creates the bucket once, puts, gets and deletes with signed requests")
  void roundTrip() {
    S3AttachmentStore s = store(endpoint(), "ak");
    byte[] bytes = {1, 2, 3, 4};
    String key = "households/h/attachments/a";

    s.put(key, "image/png", bytes);
    s.put(key + "2", "image/png", bytes);

    assertThat(requests)
        .containsExactly(
            "HEAD /media", "PUT /media", "PUT /media/" + key, "PUT /media/" + key + "2");
    assertThat(s.get(key)).hasValueSatisfying(b -> assertThat(b).isEqualTo(bytes));
    assertThat(s.get("missing")).isEmpty();
    s.delete(key);
    s.delete(key); // missing key is fine
    assertThat(s.get(key)).isEmpty();
    for (String auth : authHeaders) {
      assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=ak/");
      assertThat(auth).contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
      assertThat(auth.substring(auth.indexOf('|') + 1)).hasSize(64);
    }
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 not configured, unreachable or failing storage is Unavailable")
  void unavailable() throws IOException {
    S3AttachmentStore none = store("", "");
    assertThatThrownBy(() -> none.put("k", "image/png", new byte[1]))
        .isInstanceOf(AttachmentStore.UnavailableException.class);
    assertThatThrownBy(() -> none.get("k"))
        .isInstanceOf(AttachmentStore.UnavailableException.class);
    assertThatThrownBy(() -> none.delete("k"))
        .isInstanceOf(AttachmentStore.UnavailableException.class);

    int port = server.getAddress().getPort();
    server.stop(0);
    S3AttachmentStore down = store("http://127.0.0.1:" + port, "ak");
    assertThatThrownBy(() -> down.get("k"))
        .isInstanceOf(AttachmentStore.UnavailableException.class);

    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        ex -> {
          ex.sendResponseHeaders(403, -1);
          ex.close();
        });
    server.start();
    S3AttachmentStore forbidden = store(endpoint(), "ak");
    assertThatThrownBy(() -> forbidden.put("k", "image/png", new byte[1]))
        .isInstanceOf(AttachmentStore.UnavailableException.class)
        .hasMessageContaining("403");
    assertThat(new ArrayList<>(requests)).isEmpty();
  }
}
