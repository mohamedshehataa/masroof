package eg.masroof.domain.parsing;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Server-side OTP and promotion check (defence in depth: the Android app already drops both before
 * upload). Keywords follow the egypt-bank-sms skill; any OTP keyword wins over everything else, a
 * false positive costs one missed expense, a false negative leaks a code.
 */
public final class MessageClassifier {

  /** What a message is, before any amount extraction. */
  public enum MessageClass {
    OTP,
    PROMO,
    OTHER
  }

  private static final List<String> OTP_WORDS =
      List.of(
          "otp",
          "one-time",
          "one time",
          "passcode",
          "verification code",
          "security code",
          "code is",
          "your code",
          "do not share",
          "don't share",
          "dont share",
          "never share",
          "password",
          "valid for",
          "expires in",
          "رمز",
          "كود",
          "لا تشارك",
          "لا تقم بمشاركه",
          "لا تفصح",
          "كلمه السر",
          "كلمه المرور",
          "صالح لمده",
          "تنتهي صلاحيه");

  private static final Pattern PIN = Pattern.compile("\\bpin\\b|\\bo\\W?t\\W?p\\b");

  private static final Pattern CODE_TO_CONFIRM =
      Pattern.compile(
          "(?<!\\d)\\d{4,8}(?!\\d).{0,40}(?:to confirm|to complete|to verify" + "|لتاكيد|لاتمام)");

  private static final Pattern CODE_ENTER =
      Pattern.compile(
          "(?:\\b(?:enter|use|type|input)|ادخل|استخدم"
              + "|اكتب)\\s+(?:the\\s+)?(?:code\\s+)?(?<!\\d)\\d{4,8}(?!\\d)");

  private static final Pattern DIGITS_ONLY = Pattern.compile("^\\W*\\d{4,8}\\W*$");

  /**
   * Structural rule: a code-like word together with a standalone 4-8 digit number that is not part
   * of an amount, date, time or card last four is an OTP, whatever the rest of the sentence says
   * ("Auth code 556677 for purchase of EGP 500.00"). Same rule on the device.
   */
  private static final Pattern CODE_WORD =
      Pattern.compile(
          "\\b(?:code|pin|otp|passcode|password|verification|authentication|approve|secure"
              + "|activation|security)\\b|\\bo\\W?t\\W?p\\b"
              + "|رمز|كود|كلمه السر|كلمه المرور|الرقم السري|رقم سري|تحقق|توثيق|وافق|موافق|تفعيل");

  private static final String CURRENCY =
      "(?:(?<![a-z])(?:egp|l\\.?e|usd|eur|sar|aed|gbp)(?![a-z])|\\$|€|جنيه|ج\\.م|جم|دولار|ريال|درهم|يورو)";

  private static final List<Pattern> NOT_A_CODE =
      List.of(
          Pattern.compile(CURRENCY + "\\s*\\d[\\d.,]*"),
          Pattern.compile("\\d[\\d.,]*\\s*" + CURRENCY),
          Pattern.compile("\\d{1,4}[/-]\\d{1,2}(?:[/-]\\d{1,4})?"),
          Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2})?"),
          Pattern.compile("(?:[*x•]{2,}|\\.{3})\\s*\\d{4}"),
          Pattern.compile(
              "(?:ending(?: with| in)?|ends with|a/c|acct|account|card)\\s*(?:no\\.?|number)?"
                  + "\\s*[*x•.\\- ]*\\d{4}(?!\\d)"),
          Pattern.compile("(?:المنتهيه|منتهيه|رقم)\\s*(?:ب)?\\s*[*x. ]*\\d{4}(?!\\d)"));

  private static final Pattern STANDALONE_NUMBER =
      Pattern.compile("(?<![\\w.,/:+-])\\d{4,8}(?![\\w/:]|[.,]\\d)");

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
    if (containsAny(text, OTP_WORDS)
        || PIN.matcher(text).find()
        || CODE_TO_CONFIRM.matcher(text).find()
        || CODE_ENTER.matcher(text).find()
        || DIGITS_ONLY.matcher(text).matches()
        || looksLikeCode(text)) {
      return MessageClass.OTP;
    }
    if (containsAny(text, PROMO_WORDS) && !containsAny(text, TRANSACTION_WORDS)) {
      return MessageClass.PROMO;
    }
    return MessageClass.OTHER;
  }

  private static boolean looksLikeCode(String text) {
    if (!CODE_WORD.matcher(text).find()) {
      return false;
    }
    String rest = text;
    for (Pattern p : NOT_A_CODE) {
      rest = p.matcher(rest).replaceAll(" ");
    }
    return STANDALONE_NUMBER.matcher(rest).find();
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
