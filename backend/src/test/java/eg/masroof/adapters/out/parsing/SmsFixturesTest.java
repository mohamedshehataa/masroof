package eg.masroof.adapters.out.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import eg.masroof.domain.parsing.MessageClassifier;
import eg.masroof.domain.parsing.MessageClassifier.MessageClass;
import eg.masroof.domain.parsing.ParsedSms;
import eg.masroof.domain.parsing.SmsKind;
import eg.masroof.domain.parsing.SmsTemplateEngine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Anonymized fixtures in {@code fixtures/sms} drive the rules engine and the classifier. */
class SmsFixturesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static SmsTemplateEngine engine() throws IOException {
    try (InputStream in =
        new ClassPathResource(SmsTemplateConfiguration.RESOURCE).getInputStream()) {
      return SmsTemplateConfiguration.load(JSON.readTree(in));
    }
  }

  private static List<JsonNode> lines(String file) throws IOException {
    Path path = Path.of("..", "fixtures", "sms", file);
    List<JsonNode> out = new ArrayList<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        out.add(JSON.readTree(line));
      }
    }
    return out;
  }

  @Test
  @Tag("REQ-SMS-010")
  @Tag("REQ-SMS-012")
  @DisplayName("REQ-SMS-010 every fixture message parses to its expected fields")
  void fixturesParse() throws IOException {
    SmsTemplateEngine engine = engine();
    List<JsonNode> cases = lines("cases.jsonl");
    assertThat(cases).hasSizeGreaterThan(25);
    for (JsonNode c : cases) {
      String id = c.get("id").asString();
      String body = c.get("body").asString();
      assertThat(MessageClassifier.classify(body))
          .as(id + " is not OTP/promo")
          .isEqualTo(MessageClass.OTHER);
      Optional<ParsedSms> parsed =
          engine.parse(
              c.get("sender").asString(), body, Instant.parse(c.get("receivedAt").asString()));
      JsonNode expect = c.get("expect");
      if (expect == null || expect.isNull()) {
        assertThat(parsed).as(id).isEmpty();
        continue;
      }
      assertThat(parsed).as(id).isPresent();
      ParsedSms p = parsed.get();
      assertThat(p.kind())
          .as(id + " kind")
          .isEqualTo(SmsKind.valueOf(expect.get("kind").asString()));
      if (expect.has("amountMinor")) {
        JsonNode amount = expect.get("amountMinor");
        if (amount.isNull()) {
          assertThat(p.amount()).as(id + " amount").isNull();
        } else {
          assertThat(p.amount())
              .as(id + " amount")
              .isEqualTo(Money.of(amount.asLong(), expect.get("currency").asString()));
        }
      }
      if (expect.has("merchant")) {
        assertThat(p.merchant()).as(id + " merchant").isEqualTo(expect.get("merchant").asString());
      }
      if (expect.has("last4")) {
        assertThat(p.last4()).as(id + " last4").isEqualTo(expect.get("last4").asString());
      }
      if (expect.has("occurredAt")) {
        assertThat(p.occurredAt())
            .as(id + " occurredAt")
            .isEqualTo(Instant.parse(expect.get("occurredAt").asString()));
      }
      if (expect.has("method")) {
        assertThat(p.paymentMethod())
            .as(id + " method")
            .isEqualTo(PaymentMethod.valueOf(expect.get("method").asString()));
      }
    }
  }

  @Test
  @Tag("REQ-SMS-031")
  @Tag("REQ-SMS-032")
  @DisplayName(
      "REQ-SMS-031 server re-check: every OTP fixture is OTP (0% false negatives), promos are promos")
  void negatives() throws IOException {
    int otp = 0;
    for (JsonNode c : lines("negatives.jsonl")) {
      MessageClass expected = MessageClass.valueOf(c.get("label").asString());
      assertThat(MessageClassifier.classify(c.get("body").asString()))
          .as(c.get("id").asString())
          .isEqualTo(expected);
      if (expected == MessageClass.OTP) {
        otp++;
      }
    }
    assertThat(otp).isGreaterThanOrEqualTo(10);
  }

  @Test
  @DisplayName("REQ-SMS-010 a hostile body cannot stall the engine (step budget)")
  void hostileBodyIsBounded() throws IOException {
    SmsTemplateEngine engine = engine();
    String evil = "purchase " + "1,".repeat(900) + " at " + "a ".repeat(800);
    long start = System.nanoTime();
    Optional<ParsedSms> parsed = engine.parse("CIB", evil, Instant.parse("2026-03-12T12:10:00Z"));
    long millis = (System.nanoTime() - start) / 1_000_000;
    assertThat(millis).isLessThan(5_000);
    assertThat(parsed.map(ParsedSms::amount)).isNotNull();
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName("REQ-SMS-010 senders are matched by normalized prefix and keep their institution")
  void senders() throws IOException {
    SmsTemplateEngine engine = engine();
    assertThat(engine.institutionOf("CIB-Bank")).hasValue("CIB");
    assertThat(engine.institutionOf("cibeg")).hasValue("CIB");
    assertThat(engine.institutionOf("Vodafone Cash")).hasValue("Vodafone Cash");
    assertThat(engine.institutionOf("e&")).hasValue("e& money");
    assertThat(engine.institutionOf("19019")).isEmpty();
    assertThat(SmsTemplateEngine.senderKey("Banque-Misr")).isEqualTo("BANQUEMISR");
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName("REQ-SMS-010 two-digit years, dotted and ISO dates, am/pm and Arabic am/pm")
  void dateForms() throws IOException {
    SmsTemplateEngine engine = engine();
    Instant received = Instant.parse("2026-03-12T12:10:00Z");
    assertThat(occurred(engine, "Purchase of EGP 10.00 at X on 05.03.26 02:30 PM", received))
        .isEqualTo(Instant.parse("2026-03-05T12:30:00Z"));
    assertThat(occurred(engine, "Purchase of EGP 10.00 at X on 2026-03-04 9:05 ص", received))
        .isEqualTo(Instant.parse("2026-03-04T07:05:00Z"));
    assertThat(occurred(engine, "Purchase of EGP 10.00 at X on 2026-03-04 9:05 م", received))
        .isEqualTo(Instant.parse("2026-03-04T19:05:00Z"));
    assertThat(occurred(engine, "Purchase of EGP 10.00 at X on 31/02/2026 09:00", received))
        .isEqualTo(received);
    assertThat(occurred(engine, "Purchase of EGP 10.00 at X on 05/03/2026 25:99", received))
        .isEqualTo(Instant.parse("2026-03-05T10:00:00Z"));
    // summer time: Cairo is UTC+3 after the last Friday of April
    assertThat(
            occurred(
                engine,
                "Purchase of EGP 10.00 at X on 15/07/2026 10:00",
                Instant.parse("2026-07-15T08:00:00Z")))
        .isEqualTo(Instant.parse("2026-07-15T07:00:00Z"));
  }

  private static Instant occurred(SmsTemplateEngine engine, String body, Instant received) {
    return engine.parse("CIB", body, received).orElseThrow().occurredAt();
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName(
      "REQ-SMS-010 amount edge cases: ambiguous number is not guessed, zero is not an expense")
  void amountEdges() throws IOException {
    SmsTemplateEngine engine = engine();
    Instant received = Instant.parse("2026-03-12T12:10:00Z");
    assertThat(engine.parse("CIB", "Purchase of EGP 1,2,3 at X", received)).isEmpty();
    assertThat(engine.parse("CIB", "Purchase of EGP 0.00 at X", received)).isEmpty();
    assertThat(engine.parse("CIB", "Purchase at X", received)).isEmpty();
    Optional<ParsedSms> bare = engine.parse("CIB", "Purchase amount: 45.5 at X", received);
    assertThat(bare.orElseThrow().amount()).isEqualTo(Money.of(4550, "EGP"));
  }
}
