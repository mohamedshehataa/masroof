package eg.masroof.domain.parsing;

import eg.masroof.domain.expense.PaymentMethod;
import java.util.regex.Pattern;

/** A known sender family: how its id is recognised and how its payments are made. */
public record SenderProfile(String institution, Pattern match, PaymentMethod method) {

  /** Compiles the sender pattern, which is matched against {@link SmsTemplateEngine#senderKey}. */
  public static SenderProfile of(String institution, String match, PaymentMethod method) {
    return new SenderProfile(institution, Pattern.compile(match, Pattern.CASE_INSENSITIVE), method);
  }
}
