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

  private static final Pattern PIN = Pattern.compile("\\bpin\\b");

  private static final Pattern CODE_TO_CONFIRM =
      Pattern.compile(
          "(?<!\\d)\\d{4,8}(?!\\d).{0,40}(?:to confirm|to complete|to verify" + "|لتاكيد|لاتمام)");

  private static final Pattern CODE_ENTER =
      Pattern.compile(
          "(?:\\b(?:enter|use|type|input)|ادخل|استخدم"
              + "|اكتب)\\s+(?:the\\s+)?(?:code\\s+)?(?<!\\d)\\d{4,8}(?!\\d)");

  private static final Pattern DIGITS_ONLY = Pattern.compile("^\\W*\\d{4,8}\\W*$");

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
        || DIGITS_ONLY.matcher(text).matches()) {
      return MessageClass.OTP;
    }
    if (containsAny(text, PROMO_WORDS) && !containsAny(text, TRANSACTION_WORDS)) {
      return MessageClass.PROMO;
    }
    return MessageClass.OTHER;
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
