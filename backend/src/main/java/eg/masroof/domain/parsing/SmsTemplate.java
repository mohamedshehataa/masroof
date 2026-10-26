package eg.masroof.domain.parsing;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One rule: when the sender matches (or {@code senders} is absent) and {@code detect} is found in
 * the folded text, the message is of {@code kind}. Patterns are written against folded text (see
 * {@link TextNormalizer#fold}) and compiled case-insensitively.
 */
public record SmsTemplate(String id, @Nullable Pattern senders, SmsKind kind, Pattern detect) {

  /** Compiles the patterns; a bad pattern fails fast at load time. */
  public static SmsTemplate of(String id, @Nullable String senders, SmsKind kind, String detect) {
    return new SmsTemplate(
        id,
        senders == null ? null : Pattern.compile(senders, Pattern.CASE_INSENSITIVE),
        kind,
        Pattern.compile(detect, Pattern.CASE_INSENSITIVE));
  }
}
