package eg.masroof.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import eg.masroof.domain.auth.InviteCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Member invites on real Postgres: roles, scoping, single use, expiry, races, audit. */
@SpringBootTest(
    properties = {
      "masroof.jwt.secret=" + AuthApiIT.TEST_KEY,
      "masroof.trusted-proxies=127.0.0.0/8,::1/128",
      "masroof.invite.max-members=3"
    })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InviteApiIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "correct horse battery";
  private static final AtomicInteger IPS = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  private record Household(UUID id, UUID adminMember, String adminEmail) {}

  private static String ip() {
    return "198.51.100." + IPS.incrementAndGet();
  }

  private Household household() {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into household (id, name) values (?, 'H')", id);
    UUID member = UUID.randomUUID();
    String email = "admin-" + UUID.randomUUID() + "@example.com";
    jdbc.update(
        "insert into member (id, household_id, display_name, role) values (?, ?, 'Admin', 'ADMIN')",
        member,
        id);
    jdbc.update(
        "insert into app_user (id, member_id, email, password_hash) values (?, ?, ?, ?)",
        UUID.randomUUID(),
        member,
        email,
        new BCryptPasswordEncoder(4).encode(PASSWORD));
    return new Household(id, member, email);
  }

  private String token(String email, String password) throws Exception {
    MockHttpServletResponse r =
        mvc.perform(
                post("/auth/login")
                    .header("X-Forwarded-For", ip())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("email", email, "password", password))))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    return JSON.readTree(r.getContentAsString()).get("accessToken").asString();
  }

  private MockHttpServletResponse invite(String token, String name) throws Exception {
    return mvc.perform(
            post("/auth/invite")
                .header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", ip())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("displayName", name))))
        .andReturn()
        .getResponse();
  }

  private String code(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    return JSON.readTree(r.getContentAsString()).get("code").asString();
  }

  private MockHttpServletResponse accept(String code, String email, String password, String ip)
      throws Exception {
    return mvc.perform(
            post("/auth/invite/accept")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of("code", code, "email", email, "password", password))))
        .andReturn()
        .getResponse();
  }

  private static String freshEmail() {
    return "m-" + UUID.randomUUID() + "@example.com";
  }

  @Test
  @Tag("REQ-SEC-060")
  @Tag("REQ-SEC-061")
  @DisplayName(
      "REQ-SEC-060 REQ-SEC-061 admin invites, the member accepts, signs in, sees both members")
  void happyPath() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);
    MockHttpServletResponse created = invite(admin, "Mai");
    String code = code(created);
    assertThat(created.getHeader("Cache-Control")).contains("no-store");
    assertThat(code).hasSize(43);
    // only the hash is stored
    List<String> hashes =
        jdbc.queryForList(
            "select code_hash from invite where household_id = ?", String.class, h.id());
    assertThat(hashes).containsExactly(InviteCode.hash(code));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from invite where household_id = ? and code_hash = ?",
                Integer.class,
                h.id(),
                code))
        .isZero();

    String email = freshEmail();
    MockHttpServletResponse accepted = accept(code, email, PASSWORD, ip());
    assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(201);
    JsonNode pair = JSON.readTree(accepted.getContentAsString());
    assertThat(pair.get("member").get("role").asString()).isEqualTo("MEMBER");
    assertThat(pair.get("member").get("householdId").asString()).isEqualTo(h.id().toString());
    assertThat(pair.get("member").get("displayName").asString()).isEqualTo("Mai");

    // the new access token works and lists both members of the household
    MockHttpServletResponse members =
        mvc.perform(
                get("/api/members")
                    .header("Authorization", "Bearer " + pair.get("accessToken").asString()))
            .andReturn()
            .getResponse();
    assertThat(members.getStatus()).isEqualTo(200);
    assertThat(JSON.readTree(members.getContentAsString()).size()).isEqualTo(2);

    // refresh token from the pair rotates; login with the new password works
    assertThat(token(email, PASSWORD)).isNotBlank();

    // audit rows
    assertThat(
            jdbc.queryForList(
                "select action from audit_log where household_id = ? order by created_at",
                String.class,
                h.id()))
        .contains("INVITE_CREATED", "INVITE_ACCEPTED");
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 a MEMBER gets 403 on create, list and revoke; no token is 401")
  void membersAreForbidden() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);
    String memberEmail = freshEmail();
    assertThat(accept(code(invite(admin, "Mai")), memberEmail, PASSWORD, ip()).getStatus())
        .isEqualTo(201);
    String member = token(memberEmail, PASSWORD);

    assertThat(invite(member, "Eve").getStatus()).isEqualTo(403);
    assertThat(
            mvc.perform(get("/auth/invites").header("Authorization", "Bearer " + member))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            mvc.perform(
                    delete("/auth/invites/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + member))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            mvc.perform(
                    post("/auth/invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\"}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName(
      "REQ-SEC-060 invites are household scoped: list excludes others, revoke is 404 (IDOR)")
  void householdScoping() throws Exception {
    Household a = household();
    Household b = household();
    String adminA = token(a.adminEmail(), PASSWORD);
    String adminB = token(b.adminEmail(), PASSWORD);
    code(invite(adminA, "ForA"));
    UUID idA =
        jdbc.queryForObject("select id from invite where household_id = ?", UUID.class, a.id());

    MockHttpServletResponse listB =
        mvc.perform(get("/auth/invites").header("Authorization", "Bearer " + adminB))
            .andReturn()
            .getResponse();
    assertThat(listB.getStatus()).isEqualTo(200);
    assertThat(JSON.readTree(listB.getContentAsString()).size()).isZero();

    MockHttpServletResponse revoke =
        mvc.perform(delete("/auth/invites/" + idA).header("Authorization", "Bearer " + adminB))
            .andReturn()
            .getResponse();
    assertThat(revoke.getStatus()).isEqualTo(404);
    assertThat(jdbc.queryForObject("select status from invite where id = ?", String.class, idA))
        .isEqualTo("PENDING");

    MockHttpServletResponse listA =
        mvc.perform(get("/auth/invites").header("Authorization", "Bearer " + adminA))
            .andReturn()
            .getResponse();
    JsonNode items = JSON.readTree(listA.getContentAsString());
    assertThat(items.size()).isEqualTo(1);
    assertThat(listA.getContentAsString()).doesNotContain("code");

    // members list is household scoped too
    JsonNode members =
        JSON.readTree(
            mvc.perform(get("/api/members").header("Authorization", "Bearer " + adminB))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(members.size()).isEqualTo(1);
    assertThat(members.get(0).get("householdId").asString()).isEqualTo(b.id().toString());
  }

  @Test
  @Tag("REQ-SEC-060")
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 revoked, used, expired and unknown codes all give the same 404")
  void unusableCodesAreIndistinguishable() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);

    String revoked = code(invite(admin, "R"));
    UUID revokedId =
        jdbc.queryForObject(
            "select id from invite where code_hash = ?", UUID.class, InviteCode.hash(revoked));
    assertThat(
            mvc.perform(
                    delete("/auth/invites/" + revokedId).header("Authorization", "Bearer " + admin))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);

    String used = code(invite(admin, "U"));
    assertThat(accept(used, freshEmail(), PASSWORD, ip()).getStatus()).isEqualTo(201);

    String expired = code(invite(admin, "E"));
    jdbc.update(
        "update invite set expires_at = now() - interval '1 minute' where code_hash = ?",
        InviteCode.hash(expired));

    List<String> bodies = new ArrayList<>();
    for (String c : List.of(revoked, used, expired, "unknown-" + UUID.randomUUID())) {
      MockHttpServletResponse r = accept(c, freshEmail(), PASSWORD, ip());
      assertThat(r.getStatus()).isEqualTo(404);
      bodies.add(
          JSON.readTree(r.getContentAsString()).get("detail").asString()
              + JSON.readTree(r.getContentAsString()).get("title").asString());
      assertThat(r.getContentAsString()).doesNotContain(c);
    }
    assertThat(bodies).allMatch(b -> b.equals(bodies.get(0)));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action = 'INVITE_FAILED'", Integer.class))
        .isGreaterThanOrEqualTo(4);
    // expired invites do not appear in the list
    JsonNode list =
        JSON.readTree(
            mvc.perform(get("/auth/invites").header("Authorization", "Bearer " + admin))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(list.size()).isZero();
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 two simultaneous accepts of one code: exactly one succeeds")
  void concurrentDoubleAccept() throws Exception {
    Household h = household();
    String code = code(invite(token(h.adminEmail(), PASSWORD), "Race"));
    int n = 6;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String email = freshEmail();
      String clientIp = ip();
      Callable<Integer> task =
          () -> {
            start.await();
            return accept(code, email, PASSWORD, clientIp).getStatus();
          };
      results.add(pool.submit(task));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : results) {
      statuses.add(f.get());
    }
    pool.shutdown();
    assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
    assertThat(statuses.stream().filter(s -> s == 404).count()).isEqualTo(n - 1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from member where household_id = ?", Integer.class, h.id()))
        .isEqualTo(2);
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName(
      "REQ-SEC-061 short password is 400 and leaves the invite usable; e-mail taken is 409")
  void passwordPolicyAndEmailConflict() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);
    String code = code(invite(admin, "P"));

    MockHttpServletResponse weak = accept(code, freshEmail(), "elevenchars", ip());
    assertThat(weak.getStatus()).isEqualTo(400);
    assertThat(
            JSON.readTree(weak.getContentAsString()).get("errors").get(0).get("field").asString())
        .isEqualTo("password");
    assertThat(weak.getContentAsString()).doesNotContain("elevenchars");

    MockHttpServletResponse taken = accept(code, h.adminEmail(), PASSWORD, ip());
    assertThat(taken.getStatus()).isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "select status from invite where code_hash = ?",
                String.class,
                InviteCode.hash(code)))
        .isEqualTo("PENDING");

    assertThat(accept(code, freshEmail(), PASSWORD, ip()).getStatus()).isEqualTo(201);
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 at most 3 pending invites; a full household refuses invites (409)")
  void limits() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);
    code(invite(admin, "1"));
    code(invite(admin, "2"));
    code(invite(admin, "3"));
    assertThat(invite(admin, "4").getStatus()).isEqualTo(409);

    // revoking frees a slot
    UUID one =
        jdbc.queryForObject(
            "select id from invite where household_id = ? and display_name = '1'",
            UUID.class,
            h.id());
    mvc.perform(delete("/auth/invites/" + one).header("Authorization", "Bearer " + admin))
        .andReturn();
    code(invite(admin, "5"));

    // household member cap (3 in this test profile): two accepts fill it
    Household full = household();
    String fullAdmin = token(full.adminEmail(), PASSWORD);
    String c1 = code(invite(fullAdmin, "a"));
    String c2 = code(invite(fullAdmin, "b"));
    String c3 = code(invite(fullAdmin, "c"));
    assertThat(accept(c1, freshEmail(), PASSWORD, ip()).getStatus()).isEqualTo(201);
    assertThat(accept(c2, freshEmail(), PASSWORD, ip()).getStatus()).isEqualTo(201);
    MockHttpServletResponse over = accept(c3, freshEmail(), PASSWORD, ip());
    assertThat(over.getStatus()).isEqualTo(409);
    assertThat(invite(fullAdmin, "d").getStatus()).isEqualTo(409);
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 repeated wrong codes from one IP are rate limited with Retry-After")
  void rateLimited() throws Exception {
    String attacker = ip();
    MockHttpServletResponse last = accept("first", freshEmail(), PASSWORD, attacker);
    for (int i = 0; i < 11; i++) {
      last = accept("guess-" + i + "-" + UUID.randomUUID(), freshEmail(), PASSWORD, attacker);
    }
    assertThat(last.getStatus()).isEqualTo(429);
    assertThat(last.getHeader("Retry-After")).isNotNull();
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 audit rows hold a hashed IP prefix, never the address or the code")
  void auditHasNoSecrets() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);
    String code = code(invite(admin, "Audit"));
    accept(code, freshEmail(), PASSWORD, "203.0.113.77");
    accept("bad-" + UUID.randomUUID(), freshEmail(), PASSWORD, "203.0.113.78");
    List<String> hashes =
        jdbc.queryForList(
            "select ip_hash from audit_log where action like 'INVITE_%' and ip_hash is not null",
            String.class);
    assertThat(hashes).isNotEmpty().allMatch(s -> s.matches("[0-9a-f]{64}"));
    assertThat(hashes).noneMatch(s -> s.contains("203.0.113"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action = 'INVITE_FAILED' and household_id is null",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  private MockHttpServletResponse inviteWithEmail(String token, String name, String email)
      throws Exception {
    return mvc.perform(
            post("/auth/invite")
                .header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", ip())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("displayName", name, "email", email))))
        .andReturn()
        .getResponse();
  }

  @Test
  @Tag("REQ-SEC-062")
  @DisplayName("REQ-SEC-062 73 bytes, 64 Arabic chars and common passwords are 400, never 500")
  void passwordEdgesAre400() throws Exception {
    Household h = household();
    String code = code(invite(token(h.adminEmail(), PASSWORD), "Edge"));
    for (String pw : List.of("a".repeat(73), "ب".repeat(64), "password1234", "Password1234!")) {
      MockHttpServletResponse r = accept(code, freshEmail(), pw, ip());
      assertThat(r.getStatus()).as(pw.length() + " chars").isEqualTo(400);
      assertThat(JSON.readTree(r.getContentAsString()).get("errors").get(0).get("field").asString())
          .isEqualTo("password");
      assertThat(r.getContentAsString()).doesNotContain(pw);
    }
    // the same code still works with an acceptable 72-byte password
    assertThat(accept(code, freshEmail(), "abcdefghijklmnopqrstuvwxyz".repeat(3).substring(0, 72), ip()).getStatus())
        .isEqualTo(201);
  }

  @Test
  @Tag("REQ-SEC-060")
  @DisplayName("REQ-SEC-060 revoking an accepted invite is 409, a revoked one is idempotent")
  void revokeAfterAccept() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);
    String accepted = code(invite(admin, "Done"));
    assertThat(accept(accepted, freshEmail(), PASSWORD, ip()).getStatus()).isEqualTo(201);
    UUID acceptedId =
        jdbc.queryForObject(
            "select id from invite where code_hash = ?", UUID.class, InviteCode.hash(accepted));
    assertThat(
            mvc.perform(
                    delete("/auth/invites/" + acceptedId)
                        .header("Authorization", "Bearer " + admin))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(409);

    String pending = code(invite(admin, "Twice"));
    UUID pendingId =
        jdbc.queryForObject(
            "select id from invite where code_hash = ?", UUID.class, InviteCode.hash(pending));
    int before =
        java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "select count(*) from audit_log where action = 'INVITE_REVOKED' and household_id = ?",
                Integer.class,
                h.id()));
    for (int i = 0; i < 2; i++) {
      assertThat(
              mvc.perform(
                      delete("/auth/invites/" + pendingId)
                          .header("Authorization", "Bearer " + admin))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(204);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_log where action = 'INVITE_REVOKED' and household_id = ?",
                Integer.class,
                h.id()))
        .isEqualTo(before + 1); // audited once, for the real change
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName(
      "REQ-SEC-061 an invite with an e-mail hint accepts only that e-mail (case-insensitive)")
  void emailHint() throws Exception {
    Household h = household();
    String admin = token(h.adminEmail(), PASSWORD);
    String hint = "hint-" + UUID.randomUUID() + "@example.com";
    String code = code(inviteWithEmail(admin, "Hinted", hint));
    MockHttpServletResponse wrong = accept(code, freshEmail(), PASSWORD, ip());
    assertThat(wrong.getStatus()).isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "select status from invite where code_hash = ?",
                String.class,
                InviteCode.hash(code)))
        .isEqualTo("PENDING");
    assertThat(accept(code, hint.toUpperCase(java.util.Locale.ROOT), PASSWORD, ip()).getStatus())
        .isEqualTo(201);
  }

  @Test
  @Tag("REQ-SEC-061")
  @DisplayName("REQ-SEC-061 role and householdId in the accept body are ignored")
  void massAssignmentIgnored() throws Exception {
    Household h = household();
    Household other = household();
    String code = code(invite(token(h.adminEmail(), PASSWORD), "Mass"));
    String email = freshEmail();
    MockHttpServletResponse r =
        mvc.perform(
                post("/auth/invite/accept")
                    .header("X-Forwarded-For", ip())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(
                            Map.of(
                                "code", code,
                                "email", email,
                                "password", PASSWORD,
                                "role", "ADMIN",
                                "householdId", other.id().toString()))))
            .andReturn()
            .getResponse();
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    JsonNode member = JSON.readTree(r.getContentAsString()).get("member");
    assertThat(member.get("role").asString()).isEqualTo("MEMBER");
    assertThat(member.get("householdId").asString()).isEqualTo(h.id().toString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from member where household_id = ?", Integer.class, other.id()))
        .isEqualTo(1); // only the other household's own admin
  }
}
