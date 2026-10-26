package eg.masroof.adapters.out.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.domain.money.Money;
import eg.masroof.domain.parsing.MessageClassifier;
import eg.masroof.domain.parsing.MessageClassifier.MessageClass;
import eg.masroof.domain.parsing.ParsedSms;
import eg.masroof.domain.parsing.SmsKind;
import eg.masroof.domain.parsing.SmsRedactor;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared secret probes: after redaction no digit run of 4 or more survives except the allowed
 * amount, date, time and card last four, whatever the classifier says. The Android app runs the
 * same file against its own redactor.
 */
class SmsRedactionTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern RUN = Pattern.compile("\\d+");
  private static final Instant RECEIVED = Instant.parse("2026-03-12T12:10:00Z");

  private static List<JsonNode> lines(String file) throws IOException {
    List<JsonNode> out = new ArrayList<>();
    for (String line :
        Files.readAllLines(Path.of("..", "fixtures", "sms", file), StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        out.add(JSON.readTree(line));
      }
    }
    return out;
  }

  /** Invisible characters are written as markers in the fixture file and expanded here. */
  private static String body(JsonNode node) {
    return node.get("body")
        .asString()
        .replace("{SHY}", String.valueOf((char) 0x00AD))
        .replace("{ISEP}", String.valueOf((char) 0x2063))
        .replace("{ZWSP}", String.valueOf((char) 0x200B));
  }

  private static Set<String> set(JsonNode array) {
    java.util.HashSet<String> out = new java.util.HashSet<>();
    for (JsonNode n : array) {
      out.add(n.asString());
    }
    return out;
  }

  private static void assertRedacted(
      String id, String redacted, Set<String> secrets, Set<String> allowed) {
    Matcher m = RUN.matcher(redacted);
    while (m.find()) {
      if (m.group().length() >= 4) {
        assertThat(allowed)
            .as(id + " keeps only allowed runs, found " + m.group())
            .contains(m.group());
      }
    }
    String digitsOnly = redacted.replaceAll("\\D", "");
    for (String secret : secrets) {
      assertThat(redacted).as(id + " hides " + secret).doesNotContain(secret);
      assertThat(digitsOnly)
          .as(id + " hides " + secret + " across separators")
          .doesNotContain(secret);
    }
  }

  private static SmsTemplateEngine engine() throws IOException {
    try (InputStream in =
        new ClassPathResource(SmsTemplateConfiguration.RESOURCE).getInputStream()) {
      return SmsTemplateConfiguration.load(JSON.readTree(in));
    }
  }

  @Test
  @Tag("REQ-SMS-030")
  @DisplayName(
      "REQ-SMS-030 every probe: no secret digit run survives redaction, and the strong-signal class is right")
  void secretsNeverSurvive() throws IOException {
    List<JsonNode> probes = lines("secrets.jsonl");
    assertThat(probes.size()).isGreaterThanOrEqualTo(25);
    for (JsonNode p : probes) {
      String id = p.get("id").asString();
      String redacted = SmsRedactor.redact(body(p));
      assertRedacted(id, redacted, set(p.get("secrets")), set(p.get("allowed")));
      assertThat(SmsRedactor.redact(redacted)).as(id + " is idempotent").isEqualTo(redacted);
      if (p.has("class")) {
        assertThat(MessageClassifier.classify(body(p)))
            .as(id + " class")
            .isEqualTo(MessageClass.valueOf(p.get("class").asString()));
      }
    }
  }

  @Test
  @Tag("REQ-SMS-030")
  @DisplayName("REQ-SMS-030 digits in every script and glued to letters are masked")
  void scriptsAndGlue() {
    assertThat(SmsRedactor.redact("Code٨٨٩٩٠٠")).isEqualTo("Code••••••");
    assertThat(SmsRedactor.redact("Code۸۸۹۹۰۰ x")).isEqualTo("Code•••••• x");
    assertThat(SmsRedactor.redact("abc1234def")).isEqualTo("abc••••def");
    assertThat(SmsRedactor.redact("EGP 1,250.50 at X")).isEqualTo("EGP 1,250.50 at X");
    assertThat(SmsRedactor.redact("")).isEmpty();
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName(
      "REQ-SMS-010 genuine purchases with reference codes are kept, parsed, and their codes masked")
  void genuineTransactionsAreKept() throws IOException {
    SmsTemplateEngine engine = engine();
    List<JsonNode> cases = lines("genuine.jsonl");
    assertThat(cases).hasSize(22);
    for (JsonNode c : cases) {
      String id = c.get("id").asString();
      String text = c.get("body").asString();
      assertThat(MessageClassifier.classify(text))
          .as(id + " is not an OTP")
          .isEqualTo(MessageClass.OTHER);
      String redacted = SmsRedactor.redact(text);
      assertRedacted(id, redacted, set(c.get("secrets")), set(c.get("allowed")));
      Optional<ParsedSms> parsed = engine.parse(c.get("sender").asString(), redacted, RECEIVED);
      assertThat(parsed).as(id).isPresent();
      assertThat(parsed.get().kind())
          .as(id + " kind")
          .isEqualTo(SmsKind.valueOf(c.get("kind").asString()));
      assertThat(parsed.get().amount())
          .as(id + " amount")
          .isEqualTo(Money.of(c.get("amountMinor").asLong(), c.get("currency").asString()));
    }
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName(
      "REQ-SMS-010 the fixtures parse the same after redaction (amount, kind, time, last four)")
  void redactionKeepsWhatTheParserNeeds() throws IOException {
    SmsTemplateEngine engine = engine();
    for (JsonNode c : lines("cases.jsonl")) {
      JsonNode expect = c.get("expect");
      if (expect == null || expect.isNull()) {
        continue;
      }
      String id = c.get("id").asString();
      Instant received = Instant.parse(c.get("receivedAt").asString());
      ParsedSms plain =
          engine
              .parse(c.get("sender").asString(), c.get("body").asString(), received)
              .orElseThrow();
      ParsedSms masked =
          engine
              .parse(
                  c.get("sender").asString(),
                  SmsRedactor.redact(c.get("body").asString()),
                  received)
              .orElseThrow();
      assertThat(masked.kind()).as(id).isEqualTo(plain.kind());
      assertThat(masked.amount()).as(id).isEqualTo(plain.amount());
      assertThat(masked.occurredAt()).as(id).isEqualTo(plain.occurredAt());
      assertThat(masked.last4()).as(id).isEqualTo(plain.last4());
    }
  }
}
