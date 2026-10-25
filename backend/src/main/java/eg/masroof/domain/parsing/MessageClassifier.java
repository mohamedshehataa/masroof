package eg.masroof.domain.parsing;

import java.util.List;
import java.util.regex.Pattern;

/**
 * OTP and promotion hygiene filter. It is NOT what keeps codes secret: {@link SmsRedactor} masks
 * every digit sequence that is not an amount, date, time or card last four before anything is
 * stored, so a code that slips past this filter is already unreadable.
 *
 * <p>An OTP is recognised by strong signals only (otp, one-time, verification/security/activation/
 * login code, password, 3D Secure, approve/confirm-with, "enter 123456", "do not share", and their
 * Arabic forms). A message with a currency amount and a purchase or debit verb is a transaction
 * even when it carries "Auth code", "Approval code", a reference code or a security footer: the
 * footer sentences are ignored for it and its codes are masked.
 */
public final class MessageClassifier {

  /** What a message is, before any amount extraction. */
  public enum MessageClass {
    OTP,
    PROMO,
    OTHER
  }

  private static final int CI = Pattern.CASE_INSENSITIVE;

  private static final Pattern STRONG =
      Pattern.compile(
          "\\b[o0][ ._-]{0,2}t[ ._-]{0,2}p\\b"
              + "|one[ _-]?time"
              + "|\\b(?:verification|authentication|secur(?:e|ity)|activation|login|secret|confirmation"
              + "|temporary|dynamic)[ _-]?(?:code|number|key|pin|password|token)"
              + "|\\b(?:passcode|password|passkey|pin|token)\\b"
              + "|\\bcode is\\b|\\byour code\\b"
              + "|3[ _-]?d[ _-]?secure|\\b3ds\\b"
              + "|\\b(?:approve|authori[sz]e)\\b"
              + "|\\bconfirm\\b.{0,30}\\b(?:with|using)\\b"
              + "|\\b(?:enter|use|type|input)\\b.{0,40}(?<![\\d])\\d{4,8}(?![\\d])"
              + "|(?<![\\d])\\d{4,8}(?![\\d]).{0,40}\\bto (?:confirm|complete|verify)\\b"
              + "|\\bis your\\b.{0,30}\\b(?:code|pin|number|key|token|otp)\\b"
              + "|(?:do not|don't|dont|never) share|expires? in|valid for \\d+ ?(?:min|sec|hour)"
              + "|(?:رمز|كود) (?:ال)?(?:تحقق|تاكيد|تفعيل|دخول|امان)"
              + "|الرمز السري|رمز سري|الرقم السري|رقم سري|كلمه السر|كلمه المرور|كلمه سر|لمره واحده"
              + "|تحقق|وافق|للموافقه|الموافقه"
              + "|(?:ادخل|استخدم|اكتب)\\s+(?<![\\d])\\d{4,8}(?![\\d])"
              + "|(?<![\\d])\\d{4,8}(?![\\d]).{0,40}(?:لتاكيد|لاتمام)"
              + "|لا\\s*تشارك|لا\\s*تقم\\s*بمشاركه|لا\\s*تفصح|لا\\s*تعطي|صالح لمده|تنتهي صلاحيه",
          CI);

  /** Sentences that only warn the reader; ignored for a transaction. */
  private static final Pattern FOOTER =
      Pattern.compile(
          "(?:never|do not|don't|dont|not)\\s+share|will never ask|for security|call\\s+\\d{3,5}"
              + "|if (?:you did not|this was not|it was not)|beware|fraud|scam"
              + "|لا\\s*تشارك|لا\\s*تقم\\s*بمشاركه|لا\\s*تعطي|لا\\s*تفصح|للاستفسار|اتصل",
          CI);

  private static final Pattern SENTENCE_BREAK = Pattern.compile("(?:\\. |[!\\n؟])");

  private static final Pattern PURCHASE_VERB =
      Pattern.compile(
          "\\b(?:purchase|purchased|debited|spent|charged|withdrawn|used for|paid)\\b"
              + "|تم خصم|تم شراء|تم سحب|تم دفع|خصم مبلغ|عمليه شراء",
          CI);

  private static final Pattern DIGITS_ONLY =
      Pattern.compile("^[^\\p{L}\\p{N}]*\\d{4,8}[^\\p{L}\\p{N}]*$");

  private static final List<String> PROMO_WORDS =
      List.of(
          "offer",
          "% off",
          "cashback",
          "points",
          "rewards",
          "subscribe",
          "t&c",
          "terms apply",
          "congratulations",
          "promo",
          "coupon",
          "discount",
          "installments up to",
          "http",
          "www.",
          "عرض",
          "عروض",
          "نقاط",
          "اشترك",
          "الشروط والاحكام",
          "مبروك",
          "اربح",
          "كوبون",
          "خصم يصل",
          "تقسيط",
          "للمزيد");

  private static final List<String> TRANSACTION_WORDS =
      List.of(
          "purchase",
          "debited",
          "credited",
          "withdrawal",
          "withdrawn",
          "spent",
          "paid",
          "payment of",
          "received",
          "refund",
          "used for",
          "transaction",
          "transferred",
          "تم خصم",
          "تم شراء",
          "تم سحب",
          "تم دفع",
          "تم ايداع",
          "تم استلام",
          "تم تحويل",
          "استرداد");

  private MessageClassifier() {}

  /** Classifies a body; it is normalized here, so raw text may be passed. */
  public static MessageClass classify(String body) {
    String text = TextNormalizer.lower(TextNormalizer.fold(TextNormalizer.normalize(body)));
    if (DIGITS_ONLY.matcher(text).matches()) {
      return MessageClass.OTP;
    }
    boolean transaction = PURCHASE_VERB.matcher(text).find() && SmsRedactor.hasCurrencyAmount(text);
    String signals = transaction ? withoutFooters(text) : text;
    if (STRONG.matcher(signals).find()) {
      return MessageClass.OTP;
    }
    if (containsAny(text, PROMO_WORDS) && !containsAny(text, TRANSACTION_WORDS)) {
      return MessageClass.PROMO;
    }
    return MessageClass.OTHER;
  }

  private static String withoutFooters(String text) {
    StringBuilder kept = new StringBuilder();
    for (String sentence : SENTENCE_BREAK.splitAsStream(text).toList()) {
      if (!FOOTER.matcher(sentence).find()) {
        kept.append(sentence).append(". ");
      }
    }
    return kept.toString();
  }

  private static boolean containsAny(String text, List<String> words) {
    for (String word : words) {
      if (text.contains(word)) {
        return true;
      }
    }
    return false;
  }
}
