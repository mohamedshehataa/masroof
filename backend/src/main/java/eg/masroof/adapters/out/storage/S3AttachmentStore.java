package eg.masroof.adapters.out.storage;

import eg.masroof.application.attachment.AttachmentStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Minimal S3 (path-style, SigV4) client on {@code java.net.http}: put/get/delete object and bucket
 * creation on first use. Not configured means every call fails with {@link UnavailableException} so
 * the rest of the API is unaffected (REQ-RCPT-023). Messages carry the status only, never keys'
 * content or bytes.
 */
@Component
final class S3AttachmentStore implements AttachmentStore {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  private final @Nullable URI endpoint;
  private final String bucket;
  private final @Nullable SigV4Signer signer;
  private final HttpClient http;
  private final Clock clock;
  private volatile boolean bucketReady;

  S3AttachmentStore(
      @Value("${masroof.s3.endpoint:}") String endpoint,
      @Value("${masroof.s3.access-key:}") String accessKey,
      @Value("${masroof.s3.secret-key:}") String secretKey,
      @Value("${masroof.s3.bucket:masroof-media}") String bucket,
      @Value("${masroof.s3.region:us-east-1}") String region,
      Clock clock) {
    boolean configured = !endpoint.isBlank() && !accessKey.isBlank() && !secretKey.isBlank();
    this.endpoint = configured ? URI.create(endpoint.strip().replaceAll("/+$", "")) : null;
    this.bucket = bucket.isBlank() ? "masroof-media" : bucket.strip();
    this.signer = configured ? new SigV4Signer(accessKey, secretKey, region.strip()) : null;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    this.clock = clock;
  }

  @Override
  public void put(String key, String contentType, byte[] content) {
    ensureBucket();
    HttpResponse<byte[]> r = send("PUT", "/" + bucket + "/" + key, content, contentType);
    require(r, 200, 201, 204);
  }

  @Override
  public Optional<byte[]> get(String key) {
    HttpResponse<byte[]> r = send("GET", "/" + bucket + "/" + key, new byte[0], null);
    if (r.statusCode() == 404) {
      return Optional.empty();
    }
    require(r, 200);
    return Optional.of(r.body());
  }

  @Override
  public void delete(String key) {
    HttpResponse<byte[]> r = send("DELETE", "/" + bucket + "/" + key, new byte[0], null);
    if (r.statusCode() != 404) {
      require(r, 200, 204);
    }
  }

  private void ensureBucket() {
    if (bucketReady) {
      return;
    }
    HttpResponse<byte[]> head = send("HEAD", "/" + bucket, new byte[0], null);
    if (head.statusCode() == 404) {
      HttpResponse<byte[]> made = send("PUT", "/" + bucket, new byte[0], null);
      if (made.statusCode() != 409) { // 409: created concurrently
        require(made, 200, 201, 204);
      }
    } else {
      require(head, 200);
    }
    bucketReady = true;
  }

  private static void require(HttpResponse<byte[]> r, int... ok) {
    for (int code : ok) {
      if (r.statusCode() == code) {
        return;
      }
    }
    throw new UnavailableException("Object storage answered HTTP " + r.statusCode());
  }

  private HttpResponse<byte[]> send(
      String method, String path, byte[] body, @Nullable String contentType) {
    URI base = endpoint;
    SigV4Signer sig = signer;
    if (base == null || sig == null) {
      throw new UnavailableException("Object storage is not configured");
    }
    String canonicalUri = encodePath(path);
    String host = base.getPort() < 0 ? base.getHost() : base.getHost() + ":" + base.getPort();
    var now = clock.instant();
    String payloadHash = SigV4Signer.sha256Hex(body);
    Map<String, String> signed = new LinkedHashMap<>();
    signed.put("host", host);
    signed.put("x-amz-content-sha256", payloadHash);
    signed.put("x-amz-date", SigV4Signer.amzDate(now));
    String auth = sig.authorization(method, canonicalUri, "", signed, payloadHash, now);
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base.getScheme() + "://" + host + canonicalUri))
            .timeout(REQUEST_TIMEOUT)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", SigV4Signer.amzDate(now))
            .header("Authorization", auth)
            .method(
                method,
                body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    if (contentType != null) {
      b.header("Content-Type", contentType);
    }
    try {
      return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new UnavailableException("Object storage is not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException("Object storage call interrupted", e);
    }
  }

  /** Percent-encodes each path segment (unreserved characters stay), keeping the slashes. */
  static String encodePath(String path) {
    StringBuilder sb = new StringBuilder();
    for (byte c : path.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      int v = c & 0xFF;
      boolean unreserved =
          (v >= 'A' && v <= 'Z')
              || (v >= 'a' && v <= 'z')
              || (v >= '0' && v <= '9')
              || v == '-'
              || v == '_'
              || v == '.'
              || v == '~'
              || v == '/';
      if (unreserved) {
        sb.append((char) v);
      } else {
        sb.append('%').append(String.format("%02X", v));
      }
    }
    return sb.toString();
  }
}
