package eg.masroof.adapters.out.parsing;

import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.parsing.SenderProfile;
import eg.masroof.domain.parsing.SmsKind;
import eg.masroof.domain.parsing.SmsTemplate;
import eg.masroof.domain.parsing.SmsTemplateEngine;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds the rules engine from the template data file (STORY-044). */
@Configuration(proxyBeanMethods = false)
class SmsTemplateConfiguration {

  static final String RESOURCE = "parsing/sms-templates.json";

  @Bean
  SmsTemplateEngine smsTemplateEngine(JsonMapper json) {
    try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
      return load(json.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + RESOURCE, e);
    }
  }

  /** Builds an engine from the parsed template document. */
  static SmsTemplateEngine load(JsonNode root) {
    List<SenderProfile> profiles = new ArrayList<>();
    for (JsonNode n : root.get("senders")) {
      profiles.add(
          SenderProfile.of(
              n.get("institution").asString(),
              n.get("match").asString(),
              PaymentMethod.valueOf(n.get("method").asString())));
    }
    List<SmsTemplate> templates = new ArrayList<>();
    for (JsonNode n : root.get("templates")) {
      JsonNode senders = n.get("senders");
      templates.add(
          SmsTemplate.of(
              n.get("id").asString(),
              senders == null ? null : senders.asString(),
              SmsKind.valueOf(n.get("kind").asString()),
              n.get("detect").asString()));
    }
    return new SmsTemplateEngine(profiles, templates);
  }
}
