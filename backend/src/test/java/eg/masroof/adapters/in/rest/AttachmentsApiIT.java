package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import eg.masroof.application.attachment.AttachmentStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Receipt attachments end to end: real Postgres, real MinIO, real tokens (STORY-168). */
@SpringBootTest(
    properties = {
      "masroof.jwt.secret=" + AuthApiIT.TEST_KEY,
      "masroof.trusted-proxies=127.0.0.0/8,::1/128"
    })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttachmentsApiIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  // The image pinned in infra/docker-compose.yml.

  static final GenericContainer<?> MINIO =
      new GenericContainer<>(
              "cgr.dev/chainguard/minio@sha256:73297936408b731668ca0e835ac9add1a35cb6026a125c2f0cd476d9ef48222c")
          .withCommand("server", "/data")
          .withEnv("MINIO_ROOT_USER", "itest-user")
          .withEnv("MINIO_ROOT_PASSWORD", "itest-secret-1234")
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

  static {
    MINIO.start(); // before the Spring context reads the mapped port; Ryuk removes it at JVM exit
  }

  @DynamicPropertySource
  static void s3(DynamicPropertyRegistry registry) {
    registry.add(
        "masroof.s3.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
    registry.add("masroof.s3.access-key", () -> "itest-user");
    registry.add("masroof.s3.secret-key", () -> "itest-secret-1234");
    registry.add("masroof.s3.bucket", () -> "itest-media");
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "correct horse battery";
  private static final AtomicInteger IPS = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AttachmentStore store;

  private record Tenant(UUID householdId, String token, String expenseId) {}

  private static String ip() {
    return "203.0.113." + IPS.incrementAndGet();
  }

  private Tenant tenant() throws Exception {
    UUID household = UUID.randomUUID();
    jdbc.update("insert into household (id, name) values (?, 'H')", household);
    UUID member = UUID.randomUUID();
    String email = "att-" + UUID.randomUUID() + "@example.com";
    jdbc.update(
        "insert into member (id, household_id, display_name, role) values (?, ?, 'Admin', 'ADMIN')",
        member,
        household);
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, ?, ?)",
        UUID.randomUUID(),
        member,
        email,
        new BCryptPasswordEncoder(4).encode(PASSWORD));
    MockHttpServletResponse login =
        mvc.perform(
                post("/auth/login")
                    .header("X-Forwarded-For", ip())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
            .andReturn()
            .getResponse();
    assertThat(login.getStatus()).isEqualTo(200);
    String token = JSON.readTree(login.getContentAsString()).get("accessToken").asString();
    return new Tenant(household, token, createExpense(token));
  }

  private String createExpense(String token) throws Exception {
    MockHttpServletResponse r =
        mvc.perform(
                post("/api/expenses")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"type":"EXPENSE","amount":{"amountMinor":5000,"currency":"EGP"},\
                        "occurredAt":"2026-10-20T10:00:00Z"}"""))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    return JSON.readTree(r.getContentAsString()).get("id").asString();
  }

  private static <B extends AbstractMockHttpServletRequestBuilder<B>> B auth(B b, Tenant t) {
    return b.header("Authorization", "Bearer " + t.token());
  }

  private MockHttpServletResponse upload(
      Tenant t, String expenseId, String name, String declared, byte[] bytes) throws Exception {
    return mvc.perform(
            auth(
                multipart("/api/expenses/" + expenseId + "/attachments")
                    .file(new MockMultipartFile("file", name, declared, bytes)),
                t))
        .andReturn()
        .getResponse();
  }

  private static <B extends AbstractMockHttpServletRequestBuilder<B>> B withHeader(
      B b, String header) {
    return header.isEmpty() ? b : b.header("Authorization", header);
  }

  private MockHttpServletResponse send(RequestBuilder b) throws Exception {
    return mvc.perform(b).andReturn().getResponse();
  }

  private JsonNode json(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString());
  }

  private int rows(String expenseId) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "select count(*) from expense_attachment where expense_id = ?::uuid",
            Integer.class,
            expenseId));
  }

  @Test
  @Tag("REQ-RCPT-020")
  @Tag("REQ-RCPT-021")
  @Tag("REQ-RCPT-022")
  @Tag("REQ-RCPT-023")
  @Tag("REQ-RCPT-024")
  @Tag("REQ-RCPT-025")
  @DisplayName(
      "REQ-RCPT-020..025 upload (metadata stripped, type sniffed), list, download with safe headers, delete")
  void lifecycle() throws Exception {
    Tenant t = tenant();
    byte[] original = TestImages.jpegWithExif();
    // the declared type and file name lie; only the bytes count and the name is not kept
    MockHttpServletResponse up =
        upload(t, t.expenseId().toString(), "../../evil.html", "text/html", original);
    assertThat(up.getStatus()).as(up.getContentAsString()).isEqualTo(201);
    JsonNode created = json(up);
    String id = created.get("id").asString();
    assertThat(created.get("contentType").asString()).isEqualTo("image/jpeg");
    assertThat(created.get("expenseId").asString()).isEqualTo(t.expenseId().toString());
    assertThat(created.has("fileName")).isFalse();
    assertThat(created.get("sizeBytes").asLong()).isLessThan(original.length);

    MockHttpServletResponse list =
        send(auth(get("/api/expenses/" + t.expenseId() + "/attachments"), t));
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(json(list).get("items")).hasSize(1);
    assertThat(json(list).get("items").get(0).get("id").asString()).isEqualTo(id);

    MockHttpServletResponse down =
        send(auth(get("/api/expenses/" + t.expenseId() + "/attachments/" + id), t));
    assertThat(down.getStatus()).isEqualTo(200);
    byte[] served = down.getContentAsByteArray();
    assertThat(served).hasSize(created.get("sizeBytes").asInt());
    assertThat(TestImages.contains(served, "Exif")).isFalse();
    assertThat(TestImages.contains(served, TestImages.GPS)).isFalse();
    assertThat(TestImages.contains(served, "owner-comment")).isFalse();
    assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(served))).isNotNull();
    assertThat(down.getContentType()).isEqualTo("image/jpeg");
    assertThat(down.getHeader("Content-Disposition")).isEqualTo("attachment");
    assertThat(down.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(down.getHeader("Cache-Control")).isEqualTo("private, no-store");

    String key = "households/" + t.householdId() + "/attachments/" + id;
    assertThat(store.get(key)).isPresent();
    assertThat(
            jdbc.queryForObject(
                "select storage_key from expense_attachment where id = ?::uuid", String.class, id))
        .isEqualTo(key);

    MockHttpServletResponse del =
        send(auth(delete("/api/expenses/" + t.expenseId() + "/attachments/" + id), t));
    assertThat(del.getStatus()).isEqualTo(204);
    assertThat(rows(t.expenseId().toString())).isZero();
    assertThat(store.get(key)).isEmpty();
    assertThat(
            send(auth(delete("/api/expenses/" + t.expenseId() + "/attachments/" + id), t))
                .getStatus())
        .isEqualTo(404);
    assertThat(
            send(auth(get("/api/expenses/" + t.expenseId() + "/attachments/" + id), t)).getStatus())
        .isEqualTo(404);
  }

  @Test
  @Tag("REQ-RCPT-021")
  @DisplayName("REQ-RCPT-021 PNG and PDF are accepted; GIF, HTML, SVG, ZIP, renamed text are 415")
  void typesAndLimits() throws Exception {
    Tenant t = tenant();
    String e = t.expenseId().toString();
    MockHttpServletResponse png =
        upload(t, e, "a.bin", "application/octet-stream", TestImages.png());
    assertThat(png.getStatus()).isEqualTo(201);
    assertThat(json(png).get("contentType").asString()).isEqualTo("image/png");
    MockHttpServletResponse pdf = upload(t, e, "a", "image/jpeg", TestImages.pdf(1000));
    assertThat(pdf.getStatus()).isEqualTo(201);
    assertThat(json(pdf).get("contentType").asString()).isEqualTo("application/pdf");

    Tenant fresh = tenant();
    String f = fresh.expenseId().toString();
    for (String bad :
        new String[] {
          "GIF89a\u0001\u0000",
          "<html><script>1</script>",
          "<svg xmlns='x'/>",
          "PK\u0003\u0004zip",
          "plain text"
        }) {
      MockHttpServletResponse r =
          upload(fresh, f, "receipt.jpg", "image/jpeg", TestImages.ascii(bad));
      assertThat(r.getStatus()).as(bad).isEqualTo(415);
      assertThat(r.getContentType()).startsWith("application/problem+json");
    }
    assertThat(upload(fresh, f, "e.jpg", "image/jpeg", new byte[0]).getStatus()).isEqualTo(400);
    byte[] truncated = java.util.Arrays.copyOf(TestImages.png(), 40);
    assertThat(upload(fresh, f, "t.png", "image/png", truncated).getStatus()).isEqualTo(415);
    MockHttpServletResponse noPart =
        send(
            auth(
                multipart("/api/expenses/" + f + "/attachments")
                    .file(new MockMultipartFile("other", "x", "image/png", TestImages.png())),
                fresh));
    assertThat(noPart.getStatus()).isEqualTo(400);
    assertThat(rows(f)).isZero();
  }

  @Test
  @Tag("REQ-RCPT-021")
  @DisplayName("REQ-RCPT-021 exactly 8 MiB is stored; one byte more is 413 and nothing is stored")
  void sizeLimit() throws Exception {
    Tenant t = tenant();
    String e = t.expenseId().toString();
    assertThat(
            upload(t, e, "big.pdf", "application/pdf", TestImages.pdf(8 * 1024 * 1024)).getStatus())
        .isEqualTo(201);
    MockHttpServletResponse tooBig =
        upload(t, e, "big.pdf", "application/pdf", TestImages.pdf(8 * 1024 * 1024 + 1));
    assertThat(tooBig.getStatus()).isEqualTo(413);
    assertThat(rows(e)).isEqualTo(1);
  }

  @Test
  @Tag("REQ-RCPT-021")
  @DisplayName("REQ-RCPT-021 a 6th attachment on one expense is 409 and leaves nothing behind")
  void countLimit() throws Exception {
    Tenant t = tenant();
    String e = t.expenseId().toString();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      MockHttpServletResponse r = upload(t, e, "r.png", "image/png", TestImages.png());
      assertThat(r.getStatus()).isEqualTo(201);
      ids.add(json(r).get("id").asString());
    }
    MockHttpServletResponse sixth = upload(t, e, "r.png", "image/png", TestImages.png());
    assertThat(sixth.getStatus()).isEqualTo(409);
    assertThat(rows(e)).isEqualTo(5);
    assertThat(json(send(auth(get("/api/expenses/" + e + "/attachments"), t))).get("items"))
        .hasSize(5);
    // removing one makes room again
    assertThat(
            send(auth(delete("/api/expenses/" + e + "/attachments/" + ids.get(0)), t)).getStatus())
        .isEqualTo(204);
    assertThat(upload(t, e, "r.png", "image/png", TestImages.png()).getStatus()).isEqualTo(201);
  }

  @Test
  @Tag("REQ-RCPT-026")
  @Tag("REQ-RCPT-020")
  @Tag("REQ-RCPT-024")
  @DisplayName(
      "REQ-RCPT-026 another household, unknown ids, a deleted expense and mismatched paths are 404")
  void idor() throws Exception {
    Tenant a = tenant();
    Tenant b = tenant();
    String ea = a.expenseId().toString();
    String id = json(upload(a, ea, "r.png", "image/png", TestImages.png())).get("id").asString();
    String key = "households/" + a.householdId() + "/attachments/" + id;

    // household B on A's ids
    assertThat(send(auth(get("/api/expenses/" + ea + "/attachments"), b)).getStatus())
        .isEqualTo(404);
    assertThat(upload(b, ea, "r.png", "image/png", TestImages.png()).getStatus()).isEqualTo(404);
    assertThat(send(auth(get("/api/expenses/" + ea + "/attachments/" + id), b)).getStatus())
        .isEqualTo(404);
    assertThat(send(auth(delete("/api/expenses/" + ea + "/attachments/" + id), b)).getStatus())
        .isEqualTo(404);
    // B's own expense with A's attachment id
    String eb = b.expenseId().toString();
    assertThat(send(auth(get("/api/expenses/" + eb + "/attachments/" + id), b)).getStatus())
        .isEqualTo(404);
    assertThat(send(auth(delete("/api/expenses/" + eb + "/attachments/" + id), b)).getStatus())
        .isEqualTo(404);
    // A's own second expense with the attachment of the first (mismatched path)
    String ea2 = createExpense(a.token());
    assertThat(send(auth(get("/api/expenses/" + ea2 + "/attachments/" + id), a)).getStatus())
        .isEqualTo(404);
    assertThat(send(auth(delete("/api/expenses/" + ea2 + "/attachments/" + id), a)).getStatus())
        .isEqualTo(404);
    // unknown ids look the same as foreign ones
    String unknown = UUID.randomUUID().toString();
    MockHttpServletResponse foreign = send(auth(get("/api/expenses/" + ea + "/attachments"), b));
    MockHttpServletResponse missing =
        send(auth(get("/api/expenses/" + unknown + "/attachments"), b));
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(json(missing).get("detail")).isEqualTo(json(foreign).get("detail"));
    assertThat(upload(a, unknown, "r.png", "image/png", TestImages.png()).getStatus())
        .isEqualTo(404);
    // nothing changed for A
    assertThat(rows(ea)).isEqualTo(1);
    assertThat(store.get(key)).isPresent();
    assertThat(rows(eb)).isZero();

    // a soft-deleted expense hides its attachments
    assertThat(send(auth(delete("/api/expenses/" + ea), a)).getStatus()).isEqualTo(204);
    assertThat(send(auth(get("/api/expenses/" + ea + "/attachments"), a)).getStatus())
        .isEqualTo(404);
    assertThat(send(auth(get("/api/expenses/" + ea + "/attachments/" + id), a)).getStatus())
        .isEqualTo(404);
    assertThat(upload(a, ea, "r.png", "image/png", TestImages.png()).getStatus()).isEqualTo(404);
    assertThat(send(auth(delete("/api/expenses/" + ea + "/attachments/" + id), a)).getStatus())
        .isEqualTo(404);
  }

  @Test
  @Tag("REQ-RCPT-026")
  @DisplayName("REQ-RCPT-026 no or an invalid token is 401 on all four operations")
  void unauthenticated() throws Exception {
    Tenant a = tenant();
    String e = a.expenseId().toString();
    String id = json(upload(a, e, "r.png", "image/png", TestImages.png())).get("id").asString();
    String base = "/api/expenses/" + e + "/attachments";
    for (String header : new String[] {"", "Bearer not-a-token"}) {
      List<java.util.function.Function<String, RequestBuilder>> calls =
          List.of(
              h -> withHeader(get(base), h),
              h ->
                  withHeader(
                      multipart(base)
                          .file(
                              new MockMultipartFile(
                                  "file", "r.png", "image/png", TestImages.png())),
                      h),
              h -> withHeader(get(base + "/" + id), h),
              h -> withHeader(delete(base + "/" + id), h));
      for (var call : calls) {
        assertThat(send(call.apply(header)).getStatus()).as(header).isEqualTo(401);
      }
    }
    assertThat(rows(e)).isEqualTo(1);
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 the uploader is recorded and the object key holds ids only")
  void uploaderAndKey() throws Exception {
    Tenant t = tenant();
    JsonNode created =
        json(upload(t, t.expenseId().toString(), "secret-name.png", "image/png", TestImages.png()));
    assertThat(created.get("uploadedByMemberId").isNull()).isFalse();
    String key =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "select storage_key from expense_attachment where id = ?::uuid",
                String.class,
                created.get("id").asString()));
    assertThat(key)
        .doesNotContain("secret-name")
        .matches("households/[0-9a-f-]{36}/attachments/[0-9a-f-]{36}");
  }
}
