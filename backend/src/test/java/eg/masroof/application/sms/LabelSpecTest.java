package eg.masroof.application.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The merchant label spec is shared with the phone: both sides pass fixtures/sms/labels.jsonl. */
class LabelSpecTest {

  @Test
  @Tag("REQ-SMS-003")
  @DisplayName("REQ-SMS-003 the server normalizes labels exactly like the phone (shared fixture)")
  void sharedLabelSpec() throws Exception {
    List<String> lines =
        Files.readAllLines(Path.of("../fixtures/sms/labels.jsonl"), StandardCharsets.UTF_8);
    JsonMapper json = JsonMapper.builder().build();
    int checked = 0;
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode row = json.readTree(line);
      assertThat(ParsedTransactionService.normalizeLabel(row.get("input").asString()))
          .as(row.get("input").asString())
          .isEqualTo(row.get("expected").asString());
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(10);
  }
}
